package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence._
import com.evolutiongaming.akkaeffect.ActorContextAdapter
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

trait SnapshotterAdapter[F[_], A] {

  def receive: Actor.Receive

  def snapshotter: Snapshotter[F, A]
}

object SnapshotterAdapter {

  type Callback = Try[Unit] => Unit


  def apply[F[_] : FromFuture](
    adapter: ActorContextAdapter[F],
    actor: akka.persistence.Snapshotter
  ): SnapshotterAdapter[F, Any] = {

    val saveSnapshot = Callbacks[F, SeqNr](adapter) {
      case SaveSnapshotSuccess(a)        => (a.sequenceNr, Success(()))
      case SaveSnapshotFailure(a, cause) => (a.sequenceNr, Failure(cause))
    }

    val deleteSnapshot = Callbacks[F, SeqNr](adapter) {
      case DeleteSnapshotSuccess(a)        => (a.sequenceNr, Success(()))
      case DeleteSnapshotFailure(a, cause) => (a.sequenceNr, Failure(cause))
    }

    val deleteSnapshots = Callbacks[F, SnapshotSelectionCriteria](adapter) {
      case DeleteSnapshotsSuccess(a)        => (a, Success(()))
      case DeleteSnapshotsFailure(a, cause) => (a, Failure(cause))
    }

    apply(
      saveSnapshot = saveSnapshot,
      deleteSnapshot = deleteSnapshot,
      deleteSnapshots = deleteSnapshots,
      actor = actor)
  }

  def apply[F[_] : FromFuture](
    saveSnapshot: Callbacks[F, SeqNr],
    deleteSnapshot: Callbacks[F, SeqNr],
    deleteSnapshots: Callbacks[F, SnapshotSelectionCriteria],
    actor: akka.persistence.Snapshotter
  ): SnapshotterAdapter[F, Any] = {

    new SnapshotterAdapter[F, Any] {

      val snapshotter = new Snapshotter[F, Any] {

        def save(a: Any) = {
          saveSnapshot.add {
            actor.saveSnapshot(a)
            actor.snapshotSequenceNr
          }
        }

        def delete(seqNr: SeqNr) = {
          deleteSnapshot.add {
            val seqNr = actor.snapshotSequenceNr
            actor.deleteSnapshot(seqNr)
            seqNr
          }
        }

        def delete(criteria: SnapshotSelectionCriteria) = {
          deleteSnapshots.add {
            actor.deleteSnapshots(criteria)
            criteria
          }
        }
      }

      val receive = saveSnapshot.receive orElse deleteSnapshot.receive orElse deleteSnapshots.receive
    }
  }


  trait Callbacks[F[_], A] {

    def add(keyOf: => A): F[F[Unit]]

    def receive: Actor.Receive
  }

  object Callbacks {

    def apply[F[_] : FromFuture, A](
      adapter: ActorContextAdapter[F])(
      pf: PartialFunction[Any, (A, Try[Unit])]
    ): Callbacks[F, A] = {

      var callbacks = Map.empty[A, Callback]

      object Expected {

        def unapply(a: Any): Option[() => Unit] = {
          for {
            (key, value) <- pf.lift(a)
            callback     <- callbacks.get(key)
          } yield {
            () => {
              callbacks = callbacks - key
              callback(value)
            }
          }
        }
      }

      new Callbacks[F, A] {

        def add(keyOf: => A) = {
          adapter.get {
            val key = keyOf
            val promise = Promise[Unit]()
            val callback = (a: Try[Unit]) => {
              promise.complete(a)
              ()
            }
            callbacks = callbacks.updated(key, callback)
            val future = promise.future
            FromFuture[F].apply(future)
          }
        }

        val receive = { case Expected(f) => f() }
      }
    }
  }
}