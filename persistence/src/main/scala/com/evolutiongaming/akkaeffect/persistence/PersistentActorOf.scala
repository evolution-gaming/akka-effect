package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotSelectionCriteria}
import akka.{persistence => ap}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.collection.immutable.Seq


object PersistentActorOf {

//   TODO
//  def apply[F[_] : Concurrent : ToFuture : FromFuture : ToTry, S, C, E, R](
//    persistenceSetupOf: PersistenceSetupOf[F, S, C, E, R]
  def apply[F[_] : Sync : ToFuture : FromFuture : ToTry](
    eventSourcedOf: EventSourcedOf[F, Any, Any, Any, Any]
  ): PersistentActor = {

    new PersistentActor { actor =>

      println("new PersistentActor")
      lazy val (act, eventSourced) = {
        val act = Act.adapter(self)
        val eventSourced = {
          println("setup")
          val ctx = ActorCtx[F](act.value, context)
          eventSourcedOf(ctx)
            .adaptError { case error => PersistentActorError(s"$self failed to allocate eventSourced with $error", error) }
            .toTry
            .get
        }

        (act, eventSourced)
      }

      def errorPrefix = s"${ self.path.toStringWithoutAddress } $persistenceId"

      val ((journaller, snapshotter, append), release) = {

        val stopped = Lazy[F].of {
          Sync[F].delay[Throwable] { PersistentActorError(s"$errorPrefix has been stopped") }
        }
        val result = for {
          stopped     <- Resource.liftF(stopped)
          append      <- Append.adapter[F, Any](act.value, actor, stopped())
          journaller  <- Journaller.adapter[F, Any](act.value, append.value, actor, stopped())
          snapshotter <- Snapshotter.adapter[F](act.value, actor, stopped())
        } yield {
          (journaller, snapshotter, append)
        }

        result
          .allocated
          .toTry
          .get
      }

      val persistence = {
        implicit val fail: Fail[F] = new Fail[F] {
          def apply[A](msg: String) = PersistentActorError(s"$errorPrefix $msg").raiseError[F, A]
        }
        PersistenceVar[F, Any, Any, Any, Any](act.value, context, journaller.value, snapshotter.value)
      }

      override def preStart(): Unit = {
        println("preStart")
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
        println("onRecoveryFailure")
        // TODO
        super.onRecoveryFailure(cause, event)
      }

      override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long) = {
        println("onPersistFailure")
        val error = PersistentActorError(s"$errorPrefix persist failed for $event", cause)
        act.sync {
          append.onError(error, event, seqNr)
        }
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        val error = PersistentActorError(s"$errorPrefix persist rejected for $event", cause)
        act.sync {
          append.onError(error, event, seqNr)
        }
        super.onPersistRejected(cause, event, seqNr)
      }

      override def deleteMessages(toSequenceNr: Long) = {
        println("deleteMessages")
        super.deleteMessages(toSequenceNr)
      }

      override def loadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long) = {
        // TODO
        println("loadSnapshot")
        super.loadSnapshot(persistenceId, criteria, toSequenceNr)
      }

      override def saveSnapshot(snapshot: Any) = {
        println("saveSnapshot")
        super.saveSnapshot(snapshot)
      }

      override def deleteSnapshot(sequenceNr: Long) = {
        println("deleteSnapshot")
        super.deleteSnapshot(sequenceNr)
      }

      override def deleteSnapshots(criteria: SnapshotSelectionCriteria) = {
        println("deleteSnapshots")
        super.deleteSnapshots(criteria)
      }

      def receiveRecover: Receive = act.receive {
        case ap.SnapshotOffer(m, s) => persistence.snapshotOffer(SnapshotOffer(m, s))
        case RecoveryCompleted      => persistence.recoveryCompleted(lastSeqNr(), ReplyOf.fromActorRef(self))
        case event                  => persistence.event(event, lastSeqNr())
      }

      def receiveCommand: Receive = {

        def receiveAny: Receive = { case a => persistence.command(a, lastSeqNr(), sender()) }

        act.receive {
          journaller.receive orElse snapshotter.receive orElse receiveAny
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
