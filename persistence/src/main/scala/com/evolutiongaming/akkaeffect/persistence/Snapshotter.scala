package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{SnapshotSelectionCriteria, Snapshotter => _, _}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, Adapter}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}


trait Snapshotter[F[_], -A] {

  /**
    * @return Outer F[_] is about saving in background, inner F[_] is about saving completed
    */
  def save(a: A): F[F[Unit]]
  //  TODO def save(a: A): F[(SeqNr, F[Unit])]

  /**
    * @return Outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def delete(seqNr: SeqNr): F[F[Unit]]

  /**
    * @return Outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]]
}

object Snapshotter {

  def apply[F[_]](
    saveSnapshot: Call[F, SeqNr, Unit],
    deleteSnapshot: Call[F, SeqNr, Unit],
    deleteSnapshots: Call[F, SnapshotSelectionCriteria, Unit],
    actor: akka.persistence.Snapshotter
  ): Snapshotter[F, Any] = {

    new Snapshotter[F, Any] {

      def save(a: Any) = {
        saveSnapshot {
          actor.saveSnapshot(a)
          actor.snapshotSequenceNr
        }
      }

      def delete(seqNr: SeqNr) = {
        deleteSnapshot {
          actor.deleteSnapshot(seqNr)
          seqNr
        }
      }

      def delete(criteria: SnapshotSelectionCriteria) = {
        deleteSnapshots {
          actor.deleteSnapshots(criteria)
          criteria
        }
      }
    }
  }

  def adapter[F[_] : Concurrent : ToTry](
    act: Act,
    actor: akka.persistence.Snapshotter)(
    stopped: => Throwable
  ): Resource[F, Adapter[Snapshotter[F, Any]]] = {

    val stopped1 = Sync[F].delay { stopped }.flatMap { _.raiseError[F, Unit] }

    val saveSnapshot = Call.adapter[F, SeqNr, Unit](act, stopped1) {
      case SaveSnapshotSuccess(a)    => (a.sequenceNr, ().pure[F])
      case SaveSnapshotFailure(a, e) => (a.sequenceNr, e.raiseError[F, Unit])
    }

    val deleteSnapshot = Call.adapter[F, SeqNr, Unit](act, stopped1) {
      case DeleteSnapshotSuccess(a)    => (a.sequenceNr, ().pure[F])
      case DeleteSnapshotFailure(a, e) => (a.sequenceNr, e.raiseError[F, Unit])
    }

    val deleteSnapshots = Call.adapter[F, SnapshotSelectionCriteria, Unit](act, stopped1) {
      case DeleteSnapshotsSuccess(a)    => (a, ().pure[F])
      case DeleteSnapshotsFailure(a, e) => (a, e.raiseError[F, Unit])
    }

    for {
      saveSnapshot    <- saveSnapshot
      deleteSnapshot  <- deleteSnapshot
      deleteSnapshots <- deleteSnapshots
    } yield {
      Adapter(
        apply(
          saveSnapshot = saveSnapshot.value,
          deleteSnapshot = deleteSnapshot.value,
          deleteSnapshots = deleteSnapshots.value,
          actor = actor),
        saveSnapshot.receive orElse deleteSnapshot.receive orElse deleteSnapshots.receive)
    }
  }
}