package akka.persistence

import akka.actor.ActorSystem

import cats.syntax.all._
import cats.effect.{Async, Sync, Ref}

import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{EventStore, EventSourcedId, SeqNr, Events}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}
import com.evolutiongaming.sstream

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object EventStoreInterop {

  final class BufferOverflowException(bufferCapacity: Int, persistenceId: String)
      extends Exception(s"Events buffer with capacity $bufferCapacity was overflowed, recovery for $persistenceId failed")
      with NoStackTrace

  /** Create instance of [[EventStore]] that uses Akka Persistence journal plugin under the hood.
    *
    * Journal plugin uses "push" model to recover events (ie read events from underline DB) while [[EventStore]] provides "pull" API via
    * [[sstream.Stream]]. To overcome this limitation, the interop uses internal buffer to hold events provided by Akka' journal plugin
    * before they will be consumed (ie deleted from buffer) as [[EventStore.events]] stream. The output stream is lazy by itself and actual
    * event consumption from the buffer will happened only on the stream materialization.
    *
    * @param system
    *   Akka system
    * @param timeout
    *   maximum time between messages from Akka' journal plugin (is the next message expected)
    * @param capacity
    *   internal event buffer capacity, on oveflow will raise [[BufferOverflowException]]
    * @param journalPluginId
    *   Akka persistence journal plugin ID
    * @param eventSourcedId
    *   Akka persistence ID, unique per each actor
    * @return
    *   instance of [[EventStore]]
    */
  def apply[F[_]: Async: FromFuture: ToTry, A](
    system: ActorSystem,
    timeout: FiniteDuration,
    capacity: Int,
    journalPluginId: String,
    eventSourcedId: EventSourcedId
  ): F[EventStore[F, A]] =
    Sync[F]
      .delay {
        val actorRef = Persistence(system).journalFor(journalPluginId)
        ActorEffect.fromActor(actorRef)
      }
      .map { journaller =>
        new EventStore[F, A] {

          import sstream.FoldWhile._

          val persistenceId = eventSourcedId.value

          override def events(fromSeqNr: SeqNr): F[sstream.Stream[F, EventStore.Persisted[A]]] = {

            type Buffer = Vector[EventStore.Event[A]]

            def actor(buffer: Ref[F, Buffer]) =
              LocalActorRef[F, Unit, SeqNr]({}, timeout) {

                case (_, JournalProtocol.ReplayedMessage(persisted)) =>
                  if (persisted.deleted) ().asLeft[SeqNr].pure[F]
                  else {
                    val payload = persisted.payload.asInstanceOf[A]
                    val event   = EventStore.Event(payload, persisted.sequenceNr)

                    buffer
                      .modify { buffer =>
                        val buffer1 = buffer :+ event
                        buffer1 -> buffer1.length
                      }
                      .flatMap { lenght =>
                        if (lenght > capacity) {
                          new BufferOverflowException(capacity, persistenceId).raiseError[F, Either[Unit, SeqNr]]
                        } else {
                          ().asLeft[SeqNr].pure[F]
                        }
                      }
                  }

                case (_, JournalProtocol.RecoverySuccess(seqNr)) =>
                  seqNr.asRight[Unit].pure[F]

                case (_, JournalProtocol.ReplayMessagesFailure(error)) =>
                  error.raiseError[F, Either[Unit, SeqNr]]
              }

            for {
              buffer <- Ref[F].of[Buffer](Vector.empty)
              actor  <- actor(buffer)
              request = JournalProtocol.ReplayMessages(
                fromSequenceNr = fromSeqNr,
                toSequenceNr = SeqNr.Max,
                max = Long.MaxValue,
                persistenceId = persistenceId,
                persistentActor = actor.ref
              )
              _ <- journaller.tell(request)
            } yield new sstream.Stream[F, EventStore.Persisted[A]] {

              override def foldWhileM[L, R](l: L)(f: (L, EventStore.Persisted[A]) => F[Either[L, R]]): F[Either[L, R]] =
                l.asLeft[R]
                  .tailRecM {

                    case Left(l) =>
                      for {
                        events <- buffer.getAndSet(Vector.empty)
                        done   <- actor.get
                        result <- events.foldWhileM(l)(f)
                        result <- result match {

                          case Left(l) =>
                            done match {

                              case Some(Right(seqNr)) =>
                                val event = EventStore.HighestSeqNr(seqNr)
                                f(l, event).map { r =>
                                  r.asRight[Either[L, R]]
                                } // no more events but seqNr non 0, use PersistedSeqNr event to notify the actor

                              case Some(Left(er)) =>
                                er.raiseError[F, Either[Either[L, R], Either[L, R]]] // failure

                              case None =>
                                l.asLeft[R].asLeft[Either[L, R]].pure[F] // expecting more events
                            }

                          // Right(...), cos user-defined function [[f]] desided to stop consuming stream thus wrapping in Right to break tailRecM loop
                          case result => result.asRight[Either[L, R]].pure[F]

                        }
                      } yield result

                    case result => // cannot happen
                      result.asRight[Either[L, R]].pure[F]
                  }

            }

          }

          override def save(events: Events[EventStore.Event[A]]): F[F[SeqNr]] = {

            case class State(writes: Long, maxSeqNr: SeqNr)
            val state = State(events.size, SeqNr.Min)
            val actor = LocalActorRef[F, State, SeqNr](state, timeout) {

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
              val persistent = events.toList.map {
                case EventStore.Event(event, seqNr) => PersistentRepr(event, persistenceId = persistenceId, sequenceNr = seqNr)
              }
              AtomicWrite(persistent)
            }

            for {
              actor  <- actor
              request = JournalProtocol.WriteMessages(messages, actor.ref, 0)
              _      <- journaller.tell(request)
            } yield actor.res
          }

          override def deleteTo(seqNr: SeqNr): F[F[Unit]] = {

            val actor = LocalActorRef[F, Unit, Unit]({}, timeout) {
              case (_, DeleteMessagesSuccess(_))    => ().asRight[Unit].pure[F]
              case (_, DeleteMessagesFailure(e, _)) => e.raiseError[F, Either[Unit, Unit]]
            }

            for {
              actor  <- actor
              request = JournalProtocol.DeleteMessagesTo(persistenceId, seqNr, actor.ref)
              _      <- journaller.tell(request)
            } yield actor.res
          }
        }
      }

}
