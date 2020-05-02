package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.{persistence => ap}
import cats.effect.{Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.util.Lazy
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._


object PersistentActorOf {

  def apply[F[_]: Sync: Timer: ToFuture: FromFuture: ToTry](
    eventSourcedOf: EventSourcedOf[F, Any, Any, Any],
    timeout: FiniteDuration = 1.minute
  ): PersistentActor = {

    def await[A](fa: F[A]) = Await.result(fa.toFuture, timeout)

    new PersistentActor { actor =>

      lazy val (act, eventSourced) = {
        val act = Act.adapter(self)
        val eventSourced = {
          val actorCtx = ActorCtx[F](act.value.toSafe, context)
          act.sync {
            await {
              eventSourcedOf(actorCtx)
                .adaptError { case error =>
                  val path = self.path.toStringWithoutAddress
                  ActorError(s"$path failed to allocate eventSourced: $error", error)
                }
            }
          }
        }

        (act, eventSourced)
      }

      private def actorError(msg: String, cause: Option[Throwable]): Throwable = {
        val path = self.path.toStringWithoutAddress
        val causeStr: String = cause.foldMap { a => s": $a" }
        ActorError(s"$path $persistenceId $msg$causeStr", cause)
      }

      private implicit val fail: Fail[F] = new Fail[F] {
        def apply[A](msg: String, cause: Option[Throwable]) = {
          actorError(msg, cause).raiseError[F, A]
        }
      }

      case class Resources(
        append: Append.Adapter[F, Any],
        deleteEventsTo: DeleteEventsTo[F])

      lazy val (resources: Resources, release) = {
        val stopped = Lazy.sync[F, Throwable] { Sync[F].delay { actorError("has been stopped", none) } }
        val result = for {
          stopped        <- stopped.toResource
          act            <- act.value.toSafe.pure[Resource[F, *]]
          append         <- Append.adapter[F, Any](act, actor, stopped.get)
          deleteEventsTo <- DeleteEventsTo.of(actor, timeout)
        } yield {
          Resources(append, deleteEventsTo)
        }
        await {
          result.allocated
        }
      }

      val persistence = PersistenceVar[F, Any, Any, Any](act.value, context)

      override def preStart(): Unit = {
        super.preStart()
        act.sync {
          persistence.preStart(eventSourced)
        }
      }

      def persistenceId = eventSourced.eventSourcedId.value

      override def journalPluginId = {
        eventSourced.pluginIds.journal getOrElse super.journalPluginId
      }

      override def snapshotPluginId = {
        eventSourced.pluginIds.snapshot getOrElse super.snapshotPluginId
      }

//      override def recovery = eventSourced.recovery TODO

      override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]) = {
        // TODO should we react on this?
        super.onRecoveryFailure(cause, event)
      }

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
        case a => persistence.command(a, lastSeqNr(), sender())
      }

      override def persist[A](event: A)(f: A => Unit): Unit = {
        super.persist(event) { a => act.sync { f(a) } }
      }

      override def persistAll[A](events: Seq[A])(f: A => Unit): Unit = {
        super.persistAll(events) { a => act.sync { f(a) } }
      }

      override def persistAsync[A](event: A)(f: A => Unit): Unit = {
        super.persistAsync(event) { a => act.sync { f(a) } }
      }

      override def persistAllAsync[A](events: Seq[A])(f: A => Unit) = {
        super.persistAllAsync(events) { a => act.sync { f(a) } }
      }

      override def defer[A](event: A)(f: A => Unit): Unit = {
        super.defer(event) { a => act.sync { f(a) } }
      }

      override def deferAsync[A](event: A)(f: A => Unit): Unit = {
        super.deferAsync(event) { a => act.sync { f(a) } }
      }

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
        val journaller = Journaller[F, Any](resources.append.value, resources.deleteEventsTo).withFail(fail)
        val snapshotter = Snapshotter[F, Any](actor, timeout).withFail(fail)
        persistence.recoveryCompleted(seqNr, journaller, snapshotter)
      }

      private def lastSeqNr() = lastSequenceNr
    }
  }
}
