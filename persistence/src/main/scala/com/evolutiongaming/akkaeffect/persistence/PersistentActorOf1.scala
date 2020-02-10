package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotSelectionCriteria}
import akka.{persistence => ap}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.collection.immutable.Seq


object PersistentActorOf1 {

  def apply[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    persistenceSetupOf: PersistenceSetupOf[F, Any, Any, Any, Any]
  ): PersistentActor = {

    type State = PersistenceSetup[F, Any, Any, Any]

    new PersistentActor { actor =>

      implicit val executor = context.dispatcher

      val act = Act.adapter(self)

      // TODO use Adapter.scala
      val actorContextAdapter = ActorContextAdapter[F](act.value, context)

      println("new PersistentActor")
      val persistenceSetup = Lazy.unsafe {
        println("setup")
        val ctx = ActorCtx[F](act.value, context)
        persistenceSetupOf(ctx)
          .adaptError { case error => PersistentActorError(s"$self failed to allocate persistenceSetup with $error", error) }
          .toTry
          .get
      }

      def errorPrefix = s"${self.path.toStringWithoutAddress} $persistenceId $lastSequenceNr"

      val ((journaller, snapshotter, persist), release) = {

        val stopped = Lazy[F].of {
          Sync[F].delay[Throwable] { PersistentActorError(s"$errorPrefix has been stopped") }
        }
        val result = for {
          stopped     <- Resource.liftF(stopped)
          persist     <- Persist.adapter[F, Any](act.value, actor, stopped())
          journaller  <- Journaller.adapter[F, Any](act.value, persist.value, actor, stopped())
          snapshotter <- Snapshotter.adapter[F](act.value, actor, stopped())
        } yield {
          (journaller, snapshotter, persist)
        }

        result
          .allocated
          .toTry
          .get
      }

      val router = Router[F, Any, Any, Any](
        actorContextAdapter,
        journaller.value,
        snapshotter.value)

      val actorVar = ActorVar[F, State](act.value, context)

      override def preStart(): Unit = {
        println("preStart")
        super.preStart()

        act.sync {
          actorVar.preStart {
            persistenceSetup().some.pure[Resource[F, *]]
          }
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
        val error = PersistentActorError(s"$errorPrefix persist failed for $event", cause)
        act.sync {
          persist.onError(error, event, seqNr)
        }
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        val error = PersistentActorError(s"$errorPrefix persist rejected for $event", cause)
        act.sync {
          persist.onError(error, event, seqNr)
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
        case ap.SnapshotOffer(m, s) => router.onSnapshotOffer(SnapshotOffer(m, s))
        case RecoveryCompleted      => router.onRecoveryCompleted(lastSeqNr())
        case event                  => router.onEvent(event, lastSeqNr())
      }

      def receiveCommand: Receive = {

        def receiveAny: Receive = {
          case a => router.onCommand(a, actorContextAdapter, lastSeqNr(), ref = self, sender = sender())
        }

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
        act.sync {
          router.onPostStop(lastSeqNr()) // TODO
          for {
            _ <- actorVar.postStop()
            _ <- release.toFuture
          } yield {}
        }
        super.postStop()
      }

      private def lastSeqNr() = lastSequenceNr
    }
  }
}
