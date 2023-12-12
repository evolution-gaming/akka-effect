package akka.persistence

import akka.actor.ActorSystem

import cats.syntax.all._
import cats.effect.{Async, Sync, Ref}

import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{EventStore, EventSourcedId, SeqNr, Events}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}
import com.evolutiongaming.sstream

import scala.concurrent.duration._

object EventStoreInterop {

  def apply[F[_]: Async: FromFuture: ToTry, A](
    system: ActorSystem,
    timeout: FiniteDuration,
    journalPluginId: String,
    eventSourcedId: EventSourcedId
  ): F[EventStore[F, A]] =
    for {
      appendedSeqNr <- Ref[F].of(SeqNr.Min)
      journaller <- Sync[F].delay {
        val actorRef = Persistence(system).journalFor(journalPluginId)
        ActorEffect.fromActor(actorRef)
      }
    } yield new EventStore[F, A] {

      import sstream.FoldWhile._

      val persistenceId = eventSourcedId.value

      override def from(seqNr: SeqNr): F[sstream.Stream[F, EventStore.Event[A]]] = {

        type Buffer = Vector[EventStore.Event[A]]

        def actor(buffer: Ref[F, Buffer]) =
          LocalActorRef[F, Unit, SeqNr]({}, timeout) {

            case (_, JournalProtocol.ReplayedMessage(persisted)) =>
              if (persisted.deleted) ().asLeft[SeqNr].pure[F]
              else {
                val payload = persisted.payload.asInstanceOf[A]
                val event   = EventStore.Event(payload, persisted.sequenceNr)
                buffer.update(_ :+ event).as(().asLeft[SeqNr])
              }

            case (_, JournalProtocol.RecoverySuccess(seqNr)) =>
              appendedSeqNr
                .update(currentSeqNr => currentSeqNr max seqNr)
                .as(seqNr.asRight[Unit])

            case (_, JournalProtocol.ReplayMessagesFailure(error)) => error.raiseError[F, Either[Unit, SeqNr]]
          }

        for {
          buffer <- Ref[F].of[Buffer](Vector.empty)
          actor  <- actor(buffer)
          request = JournalProtocol.ReplayMessages(seqNr, SeqNr.Max, Long.MaxValue, persistenceId, actor.ref)
          _      <- journaller.tell(request)
        } yield new sstream.Stream[F, EventStore.Event[A]] {

          override def foldWhileM[L, R](l: L)(f: (L, EventStore.Event[A]) => F[Either[L, R]]): F[Either[L, R]] =
            l.asLeft[R]
              .tailRecM {

                case Left(l) =>
                  for {
                    events <- buffer.getAndSet(Vector.empty)
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

      override def save(events: Events[A]): F[F[SeqNr]] = {

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

        for {
          messages <- appendedSeqNr.modify { seqNr =>
            var _seqNr = seqNr
            def nextSeqNr = {
              _seqNr = _seqNr + 1
              _seqNr
            }
            val messages = events.values.toList.map { events =>
              val persistent = events.toList.map { event =>
                PersistentRepr(event, persistenceId = persistenceId, sequenceNr = nextSeqNr)
              }
              AtomicWrite(persistent)
            }
            _seqNr -> messages
          }
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
