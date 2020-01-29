package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence.{Snapshotter => _, _}
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Async, Concurrent}
import com.evolutiongaming.akkaeffect.{ActorContextAdapter, Act}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

trait SnapshotterAdapter[F[_], A] {

  def receive: Actor.Receive

  def snapshotter: Snapshotter[F, A]
}

object SnapshotterAdapter {

  type Callback = Try[Unit] => Unit


  def apply[F[_] : Async : FromFuture](
    act: Act,
    actor: akka.persistence.Snapshotter
  ): SnapshotterAdapter[F, Any] = {

    val saveSnapshot = Callbacks[F, SeqNr](act) {
      case SaveSnapshotSuccess(a)        => (a.sequenceNr, Success(()))
      case SaveSnapshotFailure(a, error) => (a.sequenceNr, Failure(error))
    }

    val deleteSnapshot = Callbacks[F, SeqNr](act) {
      case DeleteSnapshotSuccess(a)        => (a.sequenceNr, Success(()))
      case DeleteSnapshotFailure(a, error) => (a.sequenceNr, Failure(error))
    }

    val deleteSnapshots = Callbacks[F, SnapshotSelectionCriteria](act) {
      case DeleteSnapshotsSuccess(a)        => (a, Success(()))
      case DeleteSnapshotsFailure(a, error) => (a, Failure(error))
    }

    new SnapshotterAdapter[F, Any] {

      val snapshotter = new Snapshotter[F, Any] {

        def save(a: Any) = {
          saveSnapshot.schedule {
            actor.saveSnapshot(a)
            actor.snapshotSequenceNr
          }
        }

        def delete(seqNr: SeqNr) = {
          deleteSnapshot.schedule {
            actor.deleteSnapshot(seqNr)
            seqNr
          }
        }

        def delete(criteria: SnapshotSelectionCriteria) = {
          deleteSnapshots.schedule {
            actor.deleteSnapshots(criteria)
            criteria
          }
        }
      }

      val receive = saveSnapshot.receive orElse deleteSnapshot.receive orElse deleteSnapshots.receive
    }
  }


  trait Callbacks[F[_], A] {

    def schedule(call: => A): F[F[Unit]]

    def receive: Actor.Receive
  }

  object Callbacks {

    def apply[F[_] : Async : FromFuture, A](
      act: Act)(
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

        def schedule(call: => A) = {
          act.ask {
            val key = call
            val promise = Promise[Unit]()
            val callback: Callback = a => promise.complete(a)
            callbacks = callbacks.updated(key, callback)
            FromFuture[F].apply { promise.future }
          }
        }

        val receive = { case Expected(f) => f() }
      }
    }
  }
}