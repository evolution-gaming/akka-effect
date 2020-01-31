package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotSelectionCriteria}
import akka.{persistence => ap}
import cats.effect.Async
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

object PersistentActorOf1 {

  def apply[F[_] : Async : ToFuture : FromFuture : ToTry](
    persistenceSetupOf: PersistenceSetupOf[F, Any, Any, Any, Any]
  ): PersistentActor = {

    new PersistentActor { actor =>

      val act = Act.adapter(self)

      // TODO use Adapter.scala
      val actorContextAdapter = ActorContextAdapter[F](act.value, context)

      // TODO use Adapter.scala
      val eventsourcedAdapter = EventsourcedAdapter[F](actorContextAdapter, actor)

      // TODO use Adapter.scala
      val snapshotterAdapter = SnapshotterAdapter[F](act.value, actor)

      val router = Router[F, Any, Any, Any](
        actorContextAdapter,
        eventsourcedAdapter.journaller,
        snapshotterAdapter.snapshotter)

      println("new PersistentActor")
      val persistenceSetup = Lazy {
        println("setup")
        val ctx = ActorCtx[F](act.value, context)
        persistenceSetupOf(ctx)
          .handleErrorWith { error =>
            PersistentActorError(s"$self.preStart failed to allocate persistenceSetup with $error", error)
              .raiseError[F, PersistenceSetup[F, Any, Any, Any]]
          }
          .toTry
          .get
      }

      override def preStart(): Unit = {
        println("preStart")
        super.preStart()
//        router.onPreStart(persistenceSetup())
      }

      def persistenceId = persistenceSetup().persistenceId

      override def journalPluginId = {
        persistenceSetup().pluginIds.journal getOrElse super.journalPluginId
      }

      override def snapshotPluginId = {
        persistenceSetup().pluginIds.snapshot getOrElse super.snapshotPluginId
      }

      override def recovery = persistenceSetup().recovery

      override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]) = {
        // TODO
        super.onRecoveryFailure(cause, event)
      }

      override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long) = {
        eventsourcedAdapter.callbacks.onPersistFailure(cause, event, seqNr)
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        eventsourcedAdapter.callbacks.onPersistRejected(cause, event, seqNr)
        super.onPersistRejected(cause, event, seqNr)
      }

      override def deleteMessages(toSequenceNr: Long) = super.deleteMessages(toSequenceNr)

      override def loadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long) = {
        // TODO
        super.loadSnapshot(persistenceId, criteria, toSequenceNr)
      }

      override def saveSnapshot(snapshot: Any) = super.saveSnapshot(snapshot)

      override def deleteSnapshot(sequenceNr: Long) = super.deleteSnapshot(sequenceNr)

      override def deleteSnapshots(criteria: SnapshotSelectionCriteria) = super.deleteSnapshots(criteria)

      def receiveRecover: Receive = {
        case ap.SnapshotOffer(m, s) => router.onSnapshotOffer(SnapshotOffer(m, s))
        case RecoveryCompleted      => router.onRecoveryCompleted(lastSeqNr())
        case event                  => router.onEvent(event, lastSeqNr())
      }

      def receiveCommand: Receive = act.receive orElse snapshotterAdapter.receive orElse {
        case a => router.onCommand(a, actorContextAdapter, lastSeqNr(), ref = self, sender = sender())
      }

      override def postStop() = {
        router.onPostStop(lastSeqNr())
        super.postStop()
      }

      private def lastSeqNr() = lastSequenceNr
    }
  }
}

