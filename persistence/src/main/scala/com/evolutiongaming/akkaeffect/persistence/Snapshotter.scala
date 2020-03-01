package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{SnapshotSelectionCriteria, Snapshotter => _, _}
import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.FlatMap
import com.evolutiongaming.akkaeffect.{Act, Adapter}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}

import scala.util.Try


trait Snapshotter[F[_], -A] {
  import Snapshotter.Result

  /**
    * @see [[akka.persistence.Snapshotter.saveSnapshot]]
    * @return outer F[_] is about saving in background, inner F[_] is about saving completed
    */
  def save(snapshot: A): F[Result[F]]

  /**
    * @see [[akka.persistence.Snapshotter.deleteSnapshot]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def delete(seqNr: SeqNr): F[F[Unit]]

  /**
    * @see [[akka.persistence.Snapshotter.deleteSnapshots]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]]
}

object Snapshotter {

  def apply[F[_] : FlatMap](
    saveSnapshot: Call[F, SeqNr, Unit],
    deleteSnapshot: Call[F, SeqNr, Unit],
    deleteSnapshots: Call[F, SnapshotSelectionCriteria, Unit],
    actor: akka.persistence.Snapshotter
  ): Snapshotter[F, Any] = {

    new Snapshotter[F, Any] {

      def save(snapshot: Any) = {
        saveSnapshot
          .apply {
            actor.saveSnapshot(snapshot)
            actor.snapshotSequenceNr
          }
          .map { case (seqNr, a) => Result(seqNr, a) }
      }

      def delete(seqNr: SeqNr) = {
        deleteSnapshot
          .apply {
            actor.deleteSnapshot(seqNr)
            seqNr
          }
          .map { case (_, a) => a }
      }

      def delete(criteria: SnapshotSelectionCriteria) = {
        deleteSnapshots
          .apply {
            actor.deleteSnapshots(criteria)
            criteria
          }
          .map { case (_, a) => a }
      }
    }
  }

  def adapter[F[_] : Sync : ToTry : FromFuture](
    act: Act[F],
    actor: akka.persistence.Snapshotter,
    stopped: F[Throwable],
  ): Resource[F, Adapter[Snapshotter[F, Any]]] = {

    val stopped1 = stopped.flatMap { _.raiseError[F, Unit] }

    val saveSnapshot = Call.adapter[F, SeqNr, Unit](act, stopped1) {
      case SaveSnapshotSuccess(a)    => (a.sequenceNr, ().pure[Try])
      case SaveSnapshotFailure(a, e) => (a.sequenceNr, e.raiseError[Try, Unit])
    }

    val deleteSnapshot = Call.adapter[F, SeqNr, Unit](act, stopped1) {
      case DeleteSnapshotSuccess(a)    => (a.sequenceNr, ().pure[Try])
      case DeleteSnapshotFailure(a, e) => (a.sequenceNr, e.raiseError[Try, Unit])
    }

    val deleteSnapshots = Call.adapter[F, SnapshotSelectionCriteria, Unit](act, stopped1) {
      case DeleteSnapshotsSuccess(a)    => (a, ().pure[Try])
      case DeleteSnapshotsFailure(a, e) => (a, e.raiseError[Try, Unit])
    }

    for {
      saveSnapshot    <- saveSnapshot
      deleteSnapshot  <- deleteSnapshot
      deleteSnapshots <- deleteSnapshots
    } yield {
      Adapter(
        apply(
          saveSnapshot    = saveSnapshot.value,
          deleteSnapshot  = deleteSnapshot.value,
          deleteSnapshots = deleteSnapshots.value,
          actor           = actor),
        saveSnapshot.receive orElse deleteSnapshot.receive orElse deleteSnapshots.receive)
    }
  }


  final case class Result[F[_]](seqNr: SeqNr, done: F[Unit])


  implicit class SnapshotterOps[F[_], A](val self: Snapshotter[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Snapshotter[F, B] = new Snapshotter[F, B] {

      def save(snapshot: B) = f(snapshot).flatMap(self.save)

      def delete(seqNr: SeqNr) = self.delete(seqNr)

      def delete(criteria: SnapshotSelectionCriteria) = self.delete(criteria)
    }
  }
}