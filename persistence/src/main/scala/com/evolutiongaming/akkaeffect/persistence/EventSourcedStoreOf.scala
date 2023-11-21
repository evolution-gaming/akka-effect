package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{AtomicWrite, PersistentRepr, SnapshotSelectionCriteria}
import akka.persistence.journal.{AsyncRecovery, AsyncWriteJournal}
import akka.persistence.snapshot.SnapshotStore
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Clock, Ref, Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.catshelper.{FromFuture, ToTry}
import com.evolutiongaming.sstream.FoldWhile._
import com.evolutiongaming.sstream.Stream

import java.time.Instant
import scala.concurrent.Future
import scala.util.Try

object EventSourcedStoreOf {

  /**
    * [[EventSourcedStore]] implementation based on Akka Persistence API.
    *
    * The implementation delegates snapshot and events load to [[SnapshotStore]] and [[AsyncRecovery]].
    * Snapshot loaded on [[EventSourcedStore#recover]] F while events loaded lazily:
    * first events will be available for [[Stream#foldWhileM]] while tail still loaded by [[AsyncRecovery]]
    *
    * @param snapshotStore Akka Persistence snapshot (plugin)
    * @param asyncRecovery Akka Persistence journal (plugin), recovery API
    * @param asyncWrite Akka Persistence journal (plugin), storing API
    * @tparam F effect
    * @tparam S snapshot
    * @tparam E event
    * @return resource of [[EventSourcedStore]]
    */
  def fromAkka[F[_]: Async: ToTry, S, E](
    snapshotStore: SnapshotStore,
    asyncRecovery: AsyncRecovery,
    asyncWrite: AsyncWriteJournal
  ): Resource[F, EventSourcedStore[F, S, E]] = {

    val eventSourcedStore = new EventSourcedStore[F, S, E] {

      override def recover(
        id: EventSourcedId
      ): Resource[F, Recovery[F, S, E]] = {

        snapshotStore
          .loadAsync(id.value, SnapshotSelectionCriteria())
          .liftTo[F]
          .toResource
          .map { offer =>
            new Recovery[F, S, E] {

              override val snapshot: Option[Snapshot[S]] =
                offer.map { offer =>
                  new Snapshot[S] {
                    override def snapshot: S = offer.snapshot.asInstanceOf[S]

                    override def metadata: Snapshot.Metadata =
                      Snapshot.Metadata(
                        seqNr = offer.metadata.sequenceNr,
                        timestamp =
                          Instant.ofEpochMilli(offer.metadata.timestamp)
                      )
                  }
                }

              override val events: Stream[F, Event[E]] = {
                val fromSequenceNr =
                  snapshot.map(_.metadata.seqNr).getOrElse(0L)

                val stream = for {

                  buffer <- Ref[F].of(Vector.empty[Event[E]])

                  highestSequenceNr <- asyncRecovery
                    .asyncReadHighestSequenceNr(id.value, fromSequenceNr)
                    .liftTo[F]

                  replayed <- Sync[F].delay {

                    asyncRecovery.asyncReplayMessages(
                      id.value,
                      fromSequenceNr,
                      highestSequenceNr,
                      Long.MaxValue
                    ) { persisted =>
                      if (persisted.deleted) {} else {
                        val event = new Event[E] {
                          override val event: E =
                            persisted.payload.asInstanceOf[E]
                          override val seqNr: SeqNr =
                            persisted.sequenceNr
                        }
                        val _ = buffer.update(_ :+ event).toTry
                      }
                    }

                  }
                } yield {

                  new Stream[F, Event[E]] {

                    override def foldWhileM[L, R](
                      l: L
                    )(f: (L, Event[E]) => F[Either[L, R]]): F[Either[L, R]] = {

                      l.asLeft[R]
                        .tailRecM {
                          case Left(l) =>
                            for {
                              events <- buffer.getAndSet(Vector.empty[Event[E]])
                              result <- events.foldWhileM(l)(f)
                              result <- result match {

                                case l: Left[L, R] =>
                                  for {
                                    replayed <- Sync[F].delay(
                                      replayed.isCompleted
                                    )
                                  } yield
                                    if (replayed) l.asRight[Either[L, R]]
                                    else l.asLeft[Either[L, R]]

                                case result =>
                                  result.asRight[Either[L, R]].pure[F]

                              }
                            } yield result

                          case result => result.asRight[Either[L, R]].pure[F]
                        }
                    }

                  }
                }

                Stream.lift(stream).flatten
              }
            }
          }
      }

      override def journaller(id: EventSourcedId,
                              seqNr: SeqNr): Resource[F, Journaller[F, E]] = {

        def journaller(seqNr: Ref[F, SeqNr]) = new Journaller[F, E] {

          override def append: Append[F, E] = new Append[F, E] {

            override def apply(events: Events[E]): F[F[SeqNr]] = {

              val atomicWrites = events.values.toList.map { events =>
                val persistent = events.toList.map { event =>
                  PersistentRepr(event, persistenceId = id.value)
                }
                AtomicWrite(persistent)
              }

              seqNr
                .updateAndGet(_ + events.size)
                .flatMap { seqNr =>
                  Sync[F].delay {

                    asyncWrite
                      .asyncWriteMessages(atomicWrites)
                      .liftTo[F]
                      .flatMap { results =>
                        results.sequence
                          .liftTo[F]
                          .as(seqNr)
                      }

                  }
                }
            }
          }

          override def deleteTo: DeleteEventsTo[F] = new DeleteEventsTo[F] {

            override def apply(seqNr: SeqNr): F[F[Unit]] = {

              Sync[F].delay {
                asyncWrite
                  .asyncDeleteMessagesTo(id.value, seqNr)
                  .liftTo[F]

              }
            }
          }
        }

        Ref[F]
          .of(seqNr)
          .map(journaller)
          .toResource

      }

      override def snapshotter(
        id: EventSourcedId
      ): Resource[F, Snapshotter[F, S]] = {

        val snapshotter = new Snapshotter[F, S] {

          override def save(seqNr: SeqNr, snapshot: S): F[F[Instant]] = {
            for {
              timestamp <- Clock[F].realTimeInstant
              metadata = akka.persistence.SnapshotMetadata(
                id.value,
                seqNr,
                timestamp.toEpochMilli
              )
              saving <- Sync[F].delay {
                snapshotStore
                  .saveAsync(metadata, snapshot)
                  .liftTo[F]
              }
            } yield saving as timestamp
          }

          override def delete(seqNr: SeqNr): F[F[Unit]] = {
            Sync[F].delay {
              val metadata = akka.persistence.SnapshotMetadata(id.value, seqNr)
              snapshotStore.deleteAsync(metadata).liftTo[F]
            }
          }

          override def delete(
            criteria: SnapshotSelectionCriteria
          ): F[F[Unit]] = {
            Sync[F].delay {
              snapshotStore.deleteAsync(id.value, criteria).liftTo[F]
            }
          }

          override def delete(criteria: Snapshotter.Criteria): F[F[Unit]] = {
            Sync[F].delay {
              snapshotStore.deleteAsync(id.value, criteria.asAkka).liftTo[F]
            }
          }

        }

        snapshotter.pure[Resource[F, *]]

      }
    }

    eventSourcedStore.pure[Resource[F, *]]

  }

  implicit class FromFutureSyntax[A](val future: Future[A]) extends AnyVal {
    def liftTo[F[_]: FromFuture]: F[A] = FromFuture[F].apply(future)
  }

  implicit class ToTrySyntax[F[_], A](val fa: F[A]) extends AnyVal {
    def toTry(implicit F: ToTry[F]): Try[A] = F(fa)
  }

}
