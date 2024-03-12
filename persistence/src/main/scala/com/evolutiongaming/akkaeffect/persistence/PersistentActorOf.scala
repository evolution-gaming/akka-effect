package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ReceiveTimeout
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.{persistence => ap}
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.util.AtomicRef
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, Memoize, ToFuture, ToTry}

import scala.collection.immutable.Seq
import scala.concurrent.duration._

object PersistentActorOf {

  /** Describes lifecycle of entity with regards to event sourcing & PersistentActor Lifecycle phases:
    *
    *   1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery 2. Recovering : reading snapshot and
    *      replaying events 3. Receiving : receiving commands and potentially storing events & snapshots 4. Termination : triggers all
    *      release hooks of allocated resources within previous phases
    *
    * @tparam S
    *   snapshot
    * @tparam E
    *   event
    * @tparam C
    *   command
    */
  type Type[F[_], S, E, C] = EventSourcedOf[F, Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], ActorOf.Stop]]]]

  def apply[F[_]: Async: ToFuture: FromFuture: ToTry](
    eventSourcedOf: Type[F, Any, Any, Any],
    timeout: FiniteDuration = 1.minute
  ): PersistentActor = {

    new PersistentActor { actor =>
      lazy val (actorCtx, act, eventSourced) = {
        val actorCtxRef = AtomicRef(ActorCtx[F](context))
        val actorCtx    = ActorCtx.flatten(context, Sync[F].delay(actorCtxRef.get()))
        val act         = Act.Adapter(context.self)
        val eventSourced = act.sync {
          eventSourcedOf(actorCtx)
            .adaptError {
              case error =>
                val path = self.path.toStringWithoutAddress
                ActorError(s"$path failed to allocate eventSourced: $error", error)
            }
            .toTry
            .get
        }
        (actorCtxRef, act, eventSourced)
      }

      private def actorError(msg: String, cause: Option[Throwable]): Throwable = {
        val path             = self.path.toStringWithoutAddress
        val causeStr: String = cause.foldMap(a => s": $a")
        ActorError(s"$path $persistenceId $msg$causeStr", cause)
      }

      implicit private val fail: Fail[F] = new Fail[F] {
        def apply[A](msg: String, cause: Option[Throwable]) =
          actorError(msg, cause).raiseError[F, A]
      }

      case class Resources(append: Append.Adapter[F, Any], deleteEventsTo: DeleteEventsTo[F])

      lazy val (resources: Resources, release) = {
        val stopped = Memoize.sync[F, Throwable](Sync[F].delay(actorError("has been stopped", none)))
        val result = for {
          stopped        <- stopped.toResource
          act            <- act.value.pure[Resource[F, *]]
          append         <- Append.adapter[F, Any](act, actor, stopped)
          deleteEventsTo <- DeleteEventsTo.of(actor, timeout)
        } yield Resources(append, deleteEventsTo)
        result.allocated.toTry.get
      }

      val persistence: PersistenceVar[F, Any, Any, Any] = PersistenceVar[F, Any, Any, Any](act.value, context)

      override def preStart(): Unit = {
        super.preStart()
        actorCtx.set(ActorCtx[F](act.value, context))
        act.sync {
          persistence.preStart(eventSourced.value)
        }
      }

      def persistenceId = eventSourced.eventSourcedId.value

      override def journalPluginId =
        eventSourced.pluginIds.journal getOrElse super.journalPluginId

      override def snapshotPluginId =
        eventSourced.pluginIds.snapshot getOrElse super.snapshotPluginId

      override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long) = {
        val error = actorError(s"[$seqNr] persist failed for $event", cause.some)
        act.sync {
          resources.append.onError(error, event, seqNr)
        }
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        val error = actorError(s"[$seqNr] persist rejected for $event", cause.some)
        act.sync {
          resources.append.onError(error, event, seqNr)
        }
        super.onPersistRejected(cause, event, seqNr)
      }

      def receiveRecover: Receive = act.receive {
        case ap.SnapshotOffer(m, s) => persistence.snapshotOffer(lastSeqNr(), SnapshotOffer(SnapshotMetadata(m), s))
        case RecoveryCompleted      => recoveryCompleted(lastSeqNr())
        case event                  => persistence.event(lastSeqNr(), event)
      }

      def receiveCommand: Receive = act.receive {
        case ReceiveTimeout => persistence.timeout(lastSeqNr())
        case a              => persistence.command(a, lastSeqNr(), sender())
      }

      override def persist[A](event: A)(f: A => Unit): Unit =
        super.persist(event)(a => act.sync(f(a)))

      override def persistAll[A](events: Seq[A])(f: A => Unit): Unit =
        super.persistAll(events)(a => act.sync(f(a)))

      override def persistAsync[A](event: A)(f: A => Unit): Unit =
        super.persistAsync(event)(a => act.sync(f(a)))

      override def persistAllAsync[A](events: Seq[A])(f: A => Unit) =
        super.persistAllAsync(events)(a => act.sync(f(a)))

      override def defer[A](event: A)(f: A => Unit): Unit =
        super.defer(event)(a => act.sync(f(a)))

      override def deferAsync[A](event: A)(f: A => Unit): Unit =
        super.deferAsync(event)(a => act.sync(f(a)))

      override def postStop() = {
        val seqNr = lastSeqNr()
        act.sync {
          val result = for {
            _ <- persistence.postStop(seqNr)
            _ <- release
          } yield {}
          result.toFuture
        }
        super.postStop()
      }

      private def recoveryCompleted(seqNr: SeqNr): Unit = {
        val journaller  = Journaller[F, Any](resources.append.value, resources.deleteEventsTo).withFail(fail)
        val snapshotter = Snapshotter[F, Any](actor, timeout).withFail(fail)
        persistence.recoveryCompleted(seqNr, journaller, snapshotter)
      }

      private def lastSeqNr() = lastSequenceNr
    }
  }
}
