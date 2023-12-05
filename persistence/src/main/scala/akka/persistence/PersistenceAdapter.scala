package akka.persistence

import akka.actor.ActorSystem
import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{Append, DeleteEventsTo, Event, EventSourcedId, Events, SeqNr, Snapshot}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToTry}
import com.evolutiongaming.sstream.FoldWhile.FoldWhileOps
import com.evolutiongaming.sstream.Stream

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

trait PersistenceAdapter[F[_]] {

  def snapshotter[S: ClassTag](snapshotPluginId: String, persistenceId: EventSourcedId): F[PersistenceAdapter.ExtendedSnapshotter[F, S]]

  def journaller[E: ClassTag](journalPluginId: String, persistenceId: EventSourcedId): F[PersistenceAdapter.ExtendedJournaller[F, E]]
}

object PersistenceAdapter {

  trait ExtendedJournaller[F[_], E] extends com.evolutiongaming.akkaeffect.persistence.Journaller[F, E] {

    def replay(fromSequenceNr: Long, toSequenceNr: Long, max: Long): F[Stream[F, Event[E]]]

  }

  trait ExtendedSnapshotter[F[_], S] extends com.evolutiongaming.akkaeffect.persistence.Snapshotter[F, S] {

    def load(criteria: SnapshotSelectionCriteria, toSequenceNr: Long): F[F[Option[Snapshot[S]]]]

  }

  // TODO:
  // 1. set timeout for journaller ops?     IMO call-side can do it as well
  // 2. set buffer limit?                   Akka Persistence does not have such limits, should we?
  def of[F[_]: Async: ToTry: FromFuture](
    system: ActorSystem,
    askTimeout: FiniteDuration
  ): F[PersistenceAdapter[F]] = {

    val F = Async[F]

    F.delay {
      Persistence(system)
    }.map { persistence =>
      new PersistenceAdapter[F] {

        override def snapshotter[S: ClassTag](snapshotPluginId: String, eventSourcedId: EventSourcedId): F[ExtendedSnapshotter[F, S]] =
          F.delay {
            persistence.snapshotStoreFor(snapshotPluginId)
          }.map { actorRef =>
            val snapshotter = ActorEffect.fromActor(actorRef)

            new ExtendedSnapshotter[F, S] {

              val persistenceId = eventSourcedId.value

              override def load(criteria: SnapshotSelectionCriteria, toSequenceNr: Long): F[F[Option[Snapshot[S]]]] = {

                val request = SnapshotProtocol.LoadSnapshot(persistenceId, criteria, toSequenceNr)
                snapshotter
                  .ask(request, askTimeout)
                  .map { response =>
                    response.flatMap {

                      case SnapshotProtocol.LoadSnapshotResult(snapshot, _) =>
                        snapshot match {

                          case Some(offer) =>
                            offer.snapshot.castM[F, S].map { snapshot =>
                              val metadata = Snapshot.Metadata(
                                offer.metadata.sequenceNr,
                                Instant.ofEpochMilli(offer.metadata.timestamp)
                              )
                              Snapshot.const(snapshot, metadata).some
                            }

                          case None => none[Snapshot[S]].pure[F]
                        }

                      case SnapshotProtocol.LoadSnapshotFailed(err) =>
                        err.raiseError[F, Option[Snapshot[S]]]
                    }
                  }
              }

              override def save(seqNr: SeqNr, snapshot: S): F[F[Instant]] = {
                val metadata = SnapshotMetadata(persistenceId, seqNr)
                val request  = SnapshotProtocol.SaveSnapshot(metadata, snapshot)
                snapshotter
                  .ask(request, askTimeout)
                  .map { response =>
                    response.flatMap {
                      case SaveSnapshotSuccess(metadata) => Instant.ofEpochMilli(metadata.timestamp).pure[F]
                      case SaveSnapshotFailure(_, err)   => err.raiseError[F, Instant]
                    }

                  }
              }

              override def delete(seqNr: SeqNr): F[F[Unit]] = {
                val metadata = SnapshotMetadata(persistenceId, seqNr)
                val request  = SnapshotProtocol.DeleteSnapshot(metadata)
                snapshotter
                  .ask(request, askTimeout)
                  .map { response =>
                    response.flatMap {
                      case DeleteSnapshotSuccess(_)      => ().pure[F]
                      case DeleteSnapshotFailure(_, err) => err.raiseError[F, Unit]
                    }
                  }
              }

              override def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]] = {
                val request = SnapshotProtocol.DeleteSnapshots(persistenceId, criteria)
                snapshotter
                  .ask(request, askTimeout)
                  .map { response =>
                    response.flatMap {
                      case DeleteSnapshotsSuccess(_)      => ().pure[F]
                      case DeleteSnapshotsFailure(_, err) => err.raiseError[F, Unit]
                    }
                  }
              }

              override def delete(criteria: com.evolutiongaming.akkaeffect.persistence.Snapshotter.Criteria): F[F[Unit]] =
                delete(criteria.asAkka)

            }
          }

        override def journaller[E: ClassTag](journalPluginId: String, eventSourcedId: EventSourcedId): F[ExtendedJournaller[F, E]] = {

          F.delay {
            persistence.journalFor(journalPluginId)
          }.map { actorRef =>
            val journaller = ActorEffect.fromActor(actorRef)

            new ExtendedJournaller[F, E] {

              val persistenceId = eventSourcedId.value

              override def replay(fromSequenceNr: SeqNr, toSequenceNr: SeqNr, max: SeqNr): F[Stream[F, Event[E]]] = {

                def actor(buffer: Ref[F, Vector[Event[E]]]) =
                  LocalActorRef[F, Unit, SeqNr] {} {

                    case (_, JournalProtocol.ReplayedMessage(persisted)) =>
                      if (persisted.deleted) ().asLeft[SeqNr].pure[F]
                      else
                        for {
                          e    <- persisted.payload.castM[F, E]
                          event = Event.const(e, persisted.sequenceNr)
                          _    <- buffer.update(_ :+ event)
                        } yield ().asLeft[SeqNr]

                    case (_, JournalProtocol.RecoverySuccess(seqNr)) => seqNr.asRight[Unit].pure[F]

                    case (_, JournalProtocol.ReplayMessagesFailure(error)) => error.raiseError[F, Either[Unit, SeqNr]]
                  }

                for {
                  buffer <- Ref[F].of(Vector.empty[Event[E]])
                  actor  <- actor(buffer)
                  request = JournalProtocol.ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, actor.ref)
                  _      <- journaller.tell(request)
                } yield new Stream[F, Event[E]] {

                  override def foldWhileM[L, R](l: L)(f: (L, Event[E]) => F[Either[L, R]]): F[Either[L, R]] =
                    l.asLeft[R]
                      .tailRecM {

                        case Left(l) =>
                          for {
                            events <- buffer.getAndSet(Vector.empty[Event[E]])
                            done   <- actor.get
                            result <- events.foldWhileM(l)(f)
                            result <- result match {

                              case l: Left[L, R] =>
                                done match {
                                  case Some(Right(_)) => l.asRight[Either[L, R]].pure[F]                      // no more events
                                  case Some(Left(er)) => er.raiseError[F, Either[Either[L, R], Either[L, R]]] // failure
                                  case None           => l.asLeft[Either[L, R]].pure[F]                       // expecting more events
                                }

                              // Right(...), cos user-defined function [[f]] desided to stop consuming stream thus wrapping in Right to break tailRecM loop
                              case result => result.asRight[Either[L, R]].pure[F]

                            }
                          } yield result

                        case result => // cannot happened
                          result.asRight[Either[L, R]].pure[F]
                      }

                }

              }

              override def append: Append[F, E] = new Append[F, E] {

                override def apply(events: Events[E]): F[F[SeqNr]] = {

                  case class State(writes: Int, maxSeqNr: SeqNr)
                  val state = State(events.values.length, SeqNr.Min)
                  val actor = LocalActorRef[F, State, SeqNr](state) {

                    case (state, JournalProtocol.WriteMessagesSuccessful) => state.asLeft[SeqNr].pure[F]

                    case (state, JournalProtocol.WriteMessageSuccess(persistent, _)) =>
                      val seqNr = persistent.sequenceNr max state.maxSeqNr
                      val result =
                        if (state.writes == 1) seqNr.asRight[State]
                        else State(state.writes - 1, seqNr).asLeft[SeqNr]
                      result.pure[F]

                    case (_, JournalProtocol.WriteMessageRejected(_, error, _)) => error.raiseError[F, Either[State, SeqNr]]

                    case (_, JournalProtocol.WriteMessagesFailed(error, _)) => error.raiseError[F, Either[State, SeqNr]]

                    case (_, JournalProtocol.WriteMessageFailure(_, error, _)) => error.raiseError[F, Either[State, SeqNr]]
                  }

                  val messages = events.values.toList.map { events =>
                    val persistent = events.toList.map { event =>
                      PersistentRepr(event, persistenceId = persistenceId)
                    }
                    AtomicWrite(persistent)
                  }

                  for {
                    actor  <- actor
                    request = JournalProtocol.WriteMessages(messages, actor.ref, 0)
                    _      <- journaller.tell(request)
                  } yield actor.res // TODO: set timeout
                }

              }

              override def deleteTo: DeleteEventsTo[F] = new DeleteEventsTo[F] {

                override def apply(seqNr: SeqNr): F[F[Unit]] = {

                  val actor = LocalActorRef[F, Unit, Unit] {} {
                    case (_, DeleteMessagesSuccess(_))    => ().asRight[Unit].pure[F]
                    case (_, DeleteMessagesFailure(e, _)) => e.raiseError[F, Either[Unit, Unit]]
                  }

                  for {
                    actor  <- actor
                    request = JournalProtocol.DeleteMessagesTo(persistenceId, seqNr, actor.ref)
                    _      <- journaller.tell(request)
                  } yield actor.res // TODO: set timeout
                }

              }
            }
          }
        }
      }
    }

  }
}
