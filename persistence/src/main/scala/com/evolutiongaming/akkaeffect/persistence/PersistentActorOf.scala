package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.{persistence => ap}
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.collection.immutable.Seq
import scala.concurrent.duration._


object PersistentActorOf {

  def apply[F[_] : Concurrent : Timer : ToFuture : FromFuture : ToTry](
    eventSourcedOf: EventSourcedOf[F, Any, Any, Any, Any]
  ): PersistentActor = {

    new PersistentActor { actor =>

      lazy val (act, eventSourced) = {
        val act = Act.adapter(self)
        val eventSourced = {
          val ctx = ActorCtx[F](act.value.toSafe, context)
          eventSourcedOf(ctx)
            .adaptError { case error => PersistentActorError(s"$self failed to allocate eventSourced with $error", error) }
            .toTry
            .get
        }

        (act, eventSourced)
      }

      def persistentActorError(msg: String, cause: Option[Throwable]) = {
        val path = self.path.toStringWithoutAddress
        val causeStr: String = cause.foldMap { a => s": $a" }
        PersistentActorError(s"$path $persistenceId $msg$causeStr", cause)
      }

      implicit val fail: Fail[F] = new Fail[F] {
        def apply[A](msg: String, cause: Option[Throwable]) = {
          persistentActorError(msg, cause).raiseError[F, A]
        }
      }

      val timeout = 1.minute/*TODO configure*/

      val snapshotter = Snapshotter[F, Any](actor, timeout).withFail(fail)

      val ((journaller, append), release) = {

        val stopped = Lazy[F].of {
          Sync[F].delay[Throwable] { persistentActorError("has been stopped", none) }
        }
        val result = for {
          stopped     <- Resource.liftF(stopped)
          act         <- act.value.toSafe.pure[Resource[F, *]]
          append      <- Append.adapter[F, Any](act, actor, stopped.get)
          journaller  <- Journaller.adapter[F, Any](append.value, actor, stopped.get, timeout)
        } yield {
          (journaller.withFail(fail), append)
        }
        result
          .allocated
          .toTry
          .get
      }

      val persistence = PersistenceVar[F, Any, Any, Any, Any](
        act.value,
        context,
        journaller.value,
        snapshotter)

      override def preStart(): Unit = {
        super.preStart()
        act.sync {
          persistence.preStart(eventSourced)
        }
      }

      def persistenceId = eventSourced.id

      override def journalPluginId = {
        eventSourced.pluginIds.journal getOrElse super.journalPluginId
      }

      override def snapshotPluginId = {
        eventSourced.pluginIds.snapshot getOrElse super.snapshotPluginId
      }

      override def recovery = eventSourced.recovery

      override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]) = {
        // TODO should we react on this?
        super.onRecoveryFailure(cause, event)
      }

      override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long) = {
        val error = persistentActorError(s"[$seqNr] persist failed for $event", cause.some)
        act.sync {
          append.onError(error, event, seqNr)
        }
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        val error = persistentActorError(s"[$seqNr] persist rejected for $event", cause.some)
        act.sync {
          append.onError(error, event, seqNr)
        }
        super.onPersistRejected(cause, event, seqNr)
      }

      def receiveRecover: Receive = act.receive {
        case ap.SnapshotOffer(m, s) => persistence.snapshotOffer(SnapshotOffer(m, s))
        case RecoveryCompleted      => persistence.recoveryCompleted(lastSeqNr(), ReplyOf.fromActorRef(self))
        case event                  => persistence.event(event, lastSeqNr())
      }

      def receiveCommand: Receive = {

        def receiveAny: Receive = { case a => persistence.command(a, lastSeqNr(), sender()) }

        act.receive {
          journaller.receive orElse receiveAny
        }
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

      private def lastSeqNr() = lastSequenceNr
    }
  }
}
