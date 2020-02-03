package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotSelectionCriteria}
import akka.{persistence => ap}
import cats.effect.{Async, Concurrent, Resource}
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}


object PersistentActorOf1 {

  def apply[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    persistenceSetupOf: PersistenceSetupOf[F, Any, Any, Any, Any]
  ): PersistentActor = {

    type State = PersistenceSetup[F, Any, Any, Any]

    new PersistentActor { actor =>

      val act = Act.adapter(self)

      // TODO use Adapter.scala
      val actorContextAdapter = ActorContextAdapter[F](act.value, context)

      // TODO use Adapter.scala
      val eventsourcedAdapter = EventsourcedAdapter[F](actorContextAdapter, actor)

      val (snapshotter, release) = Snapshotter
        .adapter[F](act.value, actor) { PersistentActorError(s"$self has been stopped") }
        .allocated
        .toTry
        .get

      val router = Router[F, Any, Any, Any](
        actorContextAdapter,
        eventsourcedAdapter.journaller,
        snapshotter.value)

      println("new PersistentActor")
      val persistenceSetup = Lazy {
        println("setup")
        val ctx = ActorCtx[F](act.value, context)
        persistenceSetupOf(ctx)
          .adaptError { error => PersistentActorError(s"$self failed to allocate persistenceSetup with $error", error) }
          .toTry
          .get
      }

      val actorVar = ActorVar[F, State](act.value, context)

      override def preStart(): Unit = {
        println("preStart")
        super.preStart()
        actorVar.preStart {
          persistenceSetup().some.pure[Resource[F, *]]
        }
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
        println("onRecoveryFailure")
        // TODO
        super.onRecoveryFailure(cause, event)
      }

      override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long) = {
        println("onPersistFailure")
        eventsourcedAdapter.callbacks.onPersistFailure(cause, event, seqNr)
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        eventsourcedAdapter.callbacks.onPersistRejected(cause, event, seqNr)
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

      def receiveRecover: Receive = {
        case ap.SnapshotOffer(m, s) => router.onSnapshotOffer(SnapshotOffer(m, s))
        case RecoveryCompleted      => router.onRecoveryCompleted(lastSeqNr())
        case event                  => router.onEvent(event, lastSeqNr())
      }

      def receiveCommand: Receive = act.receive orElse snapshotter.receive orElse {
        case a => router.onCommand(a, actorContextAdapter, lastSeqNr(), ref = self, sender = sender())
      }

      override def postStop() = {
        release.toTry
        router.onPostStop(lastSeqNr())
        actorVar.postStop()
        super.postStop()
      }

      private def lastSeqNr() = lastSequenceNr
    }
  }
}

