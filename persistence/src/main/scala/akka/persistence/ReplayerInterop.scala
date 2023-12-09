package akka.persistence

import akka.actor.ActorSystem

import cats.effect.{Async, Ref}
import cats.syntax.all._

import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{Replayer, EventSourcedId, Event, SeqNr}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}
import com.evolutiongaming.sstream.Stream
import com.evolutiongaming.sstream.FoldWhile._

import scala.concurrent.duration._

object ReplayerInterop {

  def apply[F[_]: Async: FromFuture: ToTry](
    system: ActorSystem,
    timeout: FiniteDuration
  ): F[Replayer.Of[F]] =
    Async[F]
      .delay {
        Persistence(system)
      }
      .map { persistence =>
        new Replayer.Of[F] {

          override def apply[E](journalPluginId: String, eventSourcedId: EventSourcedId): F[Replayer[F, E]] =
            Async[F]
              .delay {
                val ref = persistence.journalFor(journalPluginId)
                ActorEffect.fromActor(ref)
              }
              .map { journaller =>
                new Replayer[F, E] {

                  val persistenceId = eventSourcedId.value

                  override def replay(fromSequenceNr: SeqNr, toSequenceNr: SeqNr, max: Long): F[Stream[F, Event[E]]] = {

                    def actor(buffer: Ref[F, Vector[Event[E]]]) =
                      LocalActorRef[F, Unit, SeqNr]({}, timeout) {

                        case (_, JournalProtocol.ReplayedMessage(persisted)) =>
                          if (persisted.deleted) ().asLeft[SeqNr].pure[F]
                          else {
                            val payload = persisted.payload.asInstanceOf[E]
                            val event   = Event.const(payload, persisted.sequenceNr)
                            buffer.update(_ :+ event).as(().asLeft[SeqNr])
                          }

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

                }
              }

        }
      }
}
