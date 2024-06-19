package akka.persistence

import cats.effect.syntax.all.*
import cats.effect.{Async, Sync}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, EventStore, Events, SeqNr}
import com.evolutiongaming.catshelper.{FromFuture, LogOf, ToTry}
import com.evolutiongaming.sstream

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

object EventStoreInterop {

  final class BufferOverflowException(bufferCapacity: Int, persistenceId: String)
      extends Exception(
        s"Events buffer with capacity $bufferCapacity was overflowed, recovery for $persistenceId failed",
      )
      with NoStackTrace

  /** Create instance of [[EventStore]] that uses Akka Persistence journal plugin under the hood.
    *
    * Journal plugin uses "push" model to recover events (i.e. read events from underline DB) while [[EventStore]]
    * provides "pull" API via [[sstream.Stream]]. To overcome this limitation, the interop uses internal buffer to hold
    * events provided by Akka' journal plugin before they will be consumed (i.e. deleted from buffer) as
    * [[EventStore.events]] stream. The output stream is lazy by itself and actual event consumption from the buffer
    * will happened only on the stream materialization.
    *
    * @param persistence
    *   Akka persistence [[Persistence]]
    * @param timeout
    *   maximum time between messages from Akka' journal plugin (is the next message expected)
    * @param capacity
    *   internal event buffer capacity, on overflow will raise [[BufferOverflowException]]
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
    eventSourcedId: EventSourcedId,
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

        trait State
        object State {
          type Buffer = Vector[EventStore.Event[Any]]
          case class Buff(buffer: Buffer)               extends State
          case class Idle(consumer: Consumer)           extends State
          case class Done(buffer: Buffer, seqNr: SeqNr) extends State
        }

        def asEvent(persisted: PersistentRepr): EventStore.Event[Any] =
          EventStore.Event(persisted.payload, persisted.sequenceNr)

        def bufferOverflow[A] =
          for {
            m <- s"events buffer overflow on recovery for entity $persistenceId. Buffer capacity is $capacity".pure[F]
            _ <- log.error(m)
            a <- new BufferOverflowException(capacity, persistenceId).raiseError[F, A]
          } yield a

        def fail[A](msg: String) =
          new IllegalStateException(s"$msg received after [RecoverySuccess]").raiseError[F, A]

        val state = State.Buff(Vector.empty)
        val actor = LocalActorRef.of[F, State, Consumer](state, timeout) { self =>
          {

            case (state, consumer: Consumer) =>
              state match {

                case State.Idle(_) => fail(s"consumer cannot be received if state is [Idle]")

                case State.Buff(buffer) if buffer.isEmpty =>
                  val state = State.Idle(consumer): State
                  state.asLeft[Consumer].pure[F]

                case State.Buff(buffer) =>
                  val task = for {
                    consumer <- buffer.foldLeftM(consumer)((consumer, event) => consumer.onEvent(event))
                    _        <- self(consumer)
                  } yield {}
                  for {
                    _ <- task.start
                  } yield State.Buff(Vector.empty).asLeft[Consumer]

                case State.Done(buffer, seqNr) =>
                  for {
                    consumer <- buffer.foldLeftM(consumer)((consumer, event) => consumer.onEvent(event))
                    consumer <- consumer.onEvent(EventStore.HighestSeqNr(seqNr))
                  } yield consumer.asRight[State]
              }

            case (state, message: JournalProtocol.ReplayedMessage) =>
              def event = asEvent(message.persistent)
              state match {

                case State.Done(_, _) => fail(s"$message received after [RecoverySuccess]")

                case State.Buff(buffer) =>
                  for {
                    _ <- if (buffer.length >= capacity) bufferOverflow else ().pure[F]
                  } yield {
                    val state = State.Buff(buffer :+ event): State
                    state.asLeft[Consumer]
                  }

                case State.Idle(consumer) =>
                  for {
                    consumer <- consumer.onEvent(event)
                  } yield State.Idle(consumer).asLeft[Consumer]
              }

            case (state, success: JournalProtocol.RecoverySuccess) =>
              state match {

                case State.Done(_, _) => fail(s"more than one $success received")

                case State.Buff(buffer) =>
                  val state = State.Done(buffer, success.highestSequenceNr): State
                  state.asLeft[Consumer].pure[F]

                case State.Idle(consumer) =>
                  for {
                    event    <- EventStore.HighestSeqNr(success.highestSequenceNr).pure[F]
                    consumer <- consumer.onEvent(event)
                  } yield consumer.asRight[State]
              }

            case (_, failure: JournalProtocol.ReplayMessagesFailure) =>
              failure.cause.raiseError[F, Either[State, Consumer]]
          }
        }

        for {
          actor <- actor
          request = JournalProtocol.ReplayMessages(
            fromSequenceNr = fromSeqNr,
            toSequenceNr = SeqNr.Max,
            max = Long.MaxValue,
            persistenceId = persistenceId,
            persistentActor = actor.ref,
          )
          _ <- journaller.tell(request)
          _ <- log.debug("recovery: events from Akka persistence requested")
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
              _ <- log.debug(s"recovery: events stream materialization started")
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
            case EventStore.Event(event, seqNr) =>
              PersistentRepr(event, persistenceId = persistenceId, sequenceNr = seqNr)
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
