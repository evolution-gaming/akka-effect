package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.journal.AsyncRecovery
import akka.persistence.snapshot.SnapshotStore
import cats.effect.{Async, Ref, Resource, Sync}
import cats.syntax.all._
import com.evolution.akkaeffect.eventsopircing.persistence.{Event, EventSourcedStore, Recovery, Snapshot}
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
    * @param journal Akka Persistence journal (plugin)
    * @tparam F effect
    * @tparam S snapshot
    * @tparam E event
    * @return resource of [[EventSourcedStore]]
    */
  def fromAkka[F[_]: Async: ToTry, S, E](snapshotStore: SnapshotStore,
                                         journal: AsyncRecovery,
  ): Resource[F, EventSourcedStore[F, S, E]] = {

    val eventSourcedStore = new EventSourcedStore[F, S, E] {

      override def recover(id: EventSourcedStore.Id): F[Recovery[F, S, E]] = {

        snapshotStore
          .loadAsync(id.value, SnapshotSelectionCriteria())
          .liftTo[F]
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

                  highestSequenceNr <- journal
                    .asyncReadHighestSequenceNr(id.value, fromSequenceNr)
                    .liftTo[F]

                  replayed <- Sync[F].delay {

                    journal.asyncReplayMessages(
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
