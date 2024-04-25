package akka.persistence

import cats.effect.syntax.all._
import cats.effect.{Async, Sync}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, EventStore, Events, SeqNr}
import com.evolutiongaming.catshelper.{FromFuture, LogOf, ToTry}
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
    * @param persistence
    *   Akka persistence [[Persistence]]
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
  def apply[F[_]: Async: FromFuture: ToTry: LogOf](
    persistence: Persistence,
    timeout: FiniteDuration,
    capacity: Int,
    journalPluginId: String,
    eventSourcedId: EventSourcedId
  ): F[EventStore[F, Any]] =
    for {
      log <- LogOf.log[F, EventStoreInterop.type]
      log <- log.prefixed(eventSourcedId.value).pure[F]
      journaller <- Sync[F].delay {
        ActorEffect.fromActor {
          persistence.journalFor(journalPluginId)
        }
      }
    } yield new EventStore[F, Any] {

      val persistenceId = eventSourcedId.value

      override def events(fromSeqNr: SeqNr): F[sstream.Stream[F, EventStore.Persisted[Any]]] = {

        trait Consumer {
          def onEvent(event: EventStore.Persisted[Any]): F[Consumer]
        }

        sealed trait State
        object State {

          object Empty                                                                   extends State
          case class Buffering(events: Vector[EventStore.Event[Any]])                    extends State
          case class Consuming(consumer: F[Consumer])                                    extends State
          case class Finishing(events: Vector[EventStore.Event[Any]], finalSeqNr: SeqNr) extends State

        }

        def event(persisted: PersistentRepr): EventStore.Event[Any] = EventStore.Event(persisted.payload, persisted.sequenceNr)

        def bufferOverflow =
          for {
            _ <- log.error(s"events buffer overflow on recovery for entity $persistenceId. Buffer capacity is $capacity")
            _ <- new BufferOverflowException(capacity, persistenceId).raiseError[F, Unit]
          } yield {}

        val actor = LocalActorRef[F, State, Consumer](State.Empty, timeout) {
          case (state, message) =>
            val effect: F[Either[State, Consumer]] = state match {

              case State.Empty =>
                message match {
                  case consumer: Consumer =>
                    State.Consuming(consumer.pure[F]).asLeft[Consumer].leftWiden[State].pure[F]

                  case JournalProtocol.ReplayedMessage(persisted) =>
                    val events = Vector(event(persisted))
                    val state1 = State.Buffering(events): State
                    state1.asLeft[Consumer].pure[F]

                  case JournalProtocol.RecoverySuccess(seqNr) =>
                    val state1 = State.Finishing(Vector.empty, seqNr): State
                    state1.asLeft[Consumer].pure[F]

                  case JournalProtocol.ReplayMessagesFailure(error) =>
                    error.raiseError[F, Either[State, Consumer]]
                }

              case state: State.Buffering =>
                message match {
                  case consumer: Consumer =>
                    for {
                      fiber <- state.events.foldLeftM(consumer) { case (c, e) => c.onEvent(e) }.start
                    } yield {
                      val joined = fiber.join.flatMap(_.embedError)
                      val state1 = State.Consuming(joined): State
                      state1.asLeft[Consumer]
                    }

                  case JournalProtocol.ReplayedMessage(persisted) =>
                    for {
                      _ <- if (state.events.length >= capacity) bufferOverflow else ().pure[F]
                    } yield {
                      val events = state.events :+ event(persisted)
                      val state1 = State.Buffering(events): State
                      state1.asLeft[Consumer]
                    }

                  case JournalProtocol.RecoverySuccess(seqNr) =>
                    val state1 = State.Finishing(state.events, seqNr): State
                    state1.asLeft[Consumer].pure[F]

                  case JournalProtocol.ReplayMessagesFailure(error) =>
                    error.raiseError[F, Either[State, Consumer]]
                }

              case state: State.Consuming =>
                message match {
                  case JournalProtocol.ReplayedMessage(persisted) =>
                    val consumer = for {
                      consumer <- state.consumer
                      consumer <- consumer.onEvent(event(persisted))
                    } yield consumer
                    val state1 = State.Consuming(consumer): State
                    state1.asLeft[Consumer].pure[F]

                  case JournalProtocol.RecoverySuccess(seqNr) =>
                    for {
                      consumer <- state.consumer
                      consumer <- consumer.onEvent(EventStore.HighestSeqNr(seqNr))
                    } yield consumer.asRight[State]

                  case JournalProtocol.ReplayMessagesFailure(error) =>
                    error.raiseError[F, Either[State, Consumer]]
                }

              case state: State.Finishing =>
                message match {
                  case consumer: Consumer =>
                    for {
                      consumer <- state.events.foldLeftM(consumer) { case (c, e) => c.onEvent(e) }
                      consumer <- consumer.onEvent(EventStore.HighestSeqNr(state.finalSeqNr))
                    } yield consumer.asRight[State]
                }

            }

            log.debug(s"recovery: receive message $message for state $state") >> effect
        }

        for {
          actor <- actor
          request = JournalProtocol.ReplayMessages(
            fromSequenceNr = fromSeqNr,
            toSequenceNr = SeqNr.Max,
            max = Long.MaxValue,
            persistenceId = persistenceId,
            persistentActor = actor.ref
          )
          _ <- journaller.tell(request)
          _ <- log.debug("recovery: events from Akka percictence requested")
        } yield new sstream.Stream[F, EventStore.Persisted[Any]] {

          override def foldWhileM[L, R](l: L)(f: (L, EventStore.Persisted[Any]) => F[Either[L, R]]): F[Either[L, R]] = {

            class TheConsumer(val state: Either[L, R]) extends Consumer { self =>

              def onEvent(event: EventStore.Persisted[Any]): F[Consumer] =
                state match {
                  case Left(state) => f(state, event) map { e => new TheConsumer(e) }
                  case _           => (self: Consumer).pure[F]
                }

            }

            for {
              _ <- log.debug(s"recovery: events stream materialisation started")
              _ <- Sync[F].delay(actor.ref ! new TheConsumer(l.asLeft[R]))
              c <- actor.res
            } yield c.asInstanceOf[TheConsumer].state
          }

        }

      }

      override def save(events: Events[EventStore.Event[Any]]): F[F[SeqNr]] = {

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
