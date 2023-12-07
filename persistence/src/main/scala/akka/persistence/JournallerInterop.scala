package akka.persistence

import akka.actor.ActorSystem

import cats.syntax.all._
import cats.effect.Async

import com.evolutiongaming.catshelper.{ToTry, FromFuture}
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{JournallerOf, EventSourcedId, Journaller, Events, SeqNr}

import scala.concurrent.duration._
import com.evolutiongaming.akkaeffect.persistence.Append
import com.evolutiongaming.akkaeffect.persistence.DeleteEventsTo

object JournallerInterop {

  def apply[F[_]: Async: ToTry: FromFuture](
    system: ActorSystem,
    timeout: FiniteDuration
  ): F[JournallerOf[F]] =
    Async[F]
      .delay {
        Persistence(system)
      }
      .map { persistence =>
        new JournallerOf[F] {

          val F = Async[F]

          override def apply[E](journalPluginId: String, eventSourcedId: EventSourcedId, currentSeqNr: SeqNr): F[Journaller[F, E]] =
            for {
              actorRef      <- F.delay(persistence.journalFor(journalPluginId))
              journaller    <- F.delay(ActorEffect.fromActor(actorRef))
              appendedSeqNr <- F.ref(currentSeqNr)
            } yield new Journaller[F, E] {

              val persistenceId = eventSourcedId.value

              override def append: Append[F, E] = new Append[F, E] {

                override def apply(events: Events[E]): F[F[SeqNr]] = {

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

              }

              override def deleteTo: DeleteEventsTo[F] = new DeleteEventsTo[F] {

                override def apply(seqNr: SeqNr): F[F[Unit]] = {

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
      }

}
