package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

import akka.persistence.{SnapshotSelectionCriteria, Snapshotter => _, _}
import cats.FlatMap
import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, MonadThrowable}

import scala.concurrent.duration.FiniteDuration


// TODO add useful methods and cats interop
trait Snapshotter[F[_], -A] {

  /**
    * @see [[akka.persistence.Snapshotter.saveSnapshot]]
    * @return outer F[_] is about saving in background, inner F[_] is about saving completed
    */
  def save(seqNr: SeqNr, snapshot: A): F[F[Instant]]

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

  def apply[F[_] : Sync : FromFuture : Fail, A](
    snapshotter: akka.persistence.Snapshotter,
    timeout: FiniteDuration
  ): Snapshotter[F, A] = {
    SnapshotterInterop(snapshotter, timeout)
  }


  implicit class SnapshotterOps[F[_], A](val self: Snapshotter[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Snapshotter[F, B] = new Snapshotter[F, B] {

      def save(seqNr: SeqNr, snapshot: B) = f(snapshot).flatMap { a => self.save(seqNr, a) }

      def delete(seqNr: SeqNr) = self.delete(seqNr)

      def delete(criteria: SnapshotSelectionCriteria) = self.delete(criteria)
    }


    def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Snapshotter[F, A] = {

      def adapt[B](msg: String)(f: F[F[B]]): F[F[B]] = {

        def adapt[C](e: Throwable) = fail[C](msg, e.some)

        f
          .handleErrorWith { e => adapt(e) }
          .map { _.handleErrorWith { e => adapt(e) } }
      }

      new Snapshotter[F, A] {

        def save(seqNr: SeqNr, snapshot: A) = {
          adapt(s"failed to save snapshot at $seqNr") {
            self.save(seqNr, snapshot)
          }
        }

        def delete(seqNr: SeqNr) = {
          adapt(s"failed to delete snapshot at $seqNr") {
            self.delete(seqNr)
          }
        }

        def delete(criteria: SnapshotSelectionCriteria) = {
          adapt(s"failed to delete snapshots for $criteria") {
            self.delete(criteria)
          }
        }
      }
    }
  }
}