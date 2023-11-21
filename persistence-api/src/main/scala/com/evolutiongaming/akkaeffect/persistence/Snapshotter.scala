package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.SnapshotSelectionCriteria
import cats.syntax.all._
import cats.{Applicative, FlatMap, ~>}
import com.evolutiongaming.akkaeffect.Fail
import com.evolutiongaming.catshelper.{Log, MeasureDuration, MonadThrowable}

import java.time.Instant

/**
  * Describes communication with underlying snapshot storage
  *
  * @tparam A - snapshot
  */
trait Snapshotter[F[_], -A] {

  /**
    * Saves a `snapshot` of this snapshotter' state.
    *
    * @return outer F[_] is about saving in background, inner F[_] is about saving completed
    */
  def save(seqNr: SeqNr, snapshot: A): F[F[Instant]]

  /**
    * Deletes the snapshot identified by `sequenceNr`.
    *
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def delete(seqNr: SeqNr): F[F[Unit]]

  /**
    * @see [[akka.persistence.Snapshotter.deleteSnapshots]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  @deprecated(
    "use Snapshotter.delete(Snapshotter.Criteria) instead",
    "01-12-2023"
  )
  def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]]

  /**
    * Deletes all snapshots matching `criteria`.
    *
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def delete(criteria: Snapshotter.Criteria): F[F[Unit]]
}

object Snapshotter {

  final case class Criteria(maxSequenceNr: Long = Long.MaxValue,
                            maxTimestamp: Long = Long.MaxValue,
                            minSequenceNr: Long = 0L,
                            minTimestamp: Long = 0L) {
    def asAkka: SnapshotSelectionCriteria =
      SnapshotSelectionCriteria(
        maxSequenceNr,
        maxTimestamp,
        minSequenceNr,
        minTimestamp
      )
  }

  object Criteria {
    def apply(criteria: SnapshotSelectionCriteria): Criteria =
      new Criteria(
        criteria.maxSequenceNr,
        criteria.maxTimestamp,
        criteria.minSequenceNr,
        criteria.minTimestamp
      )
  }

  def const[F[_], A](instant: F[F[Instant]],
                     unit: F[F[Unit]]): Snapshotter[F, A] =
    new Snapshotter[F, A] {

      def save(seqNr: SeqNr, snapshot: A) = instant

      def delete(seqNr: SeqNr) = unit

      def delete(criteria: SnapshotSelectionCriteria) = unit

      def delete(criteria: Criteria): F[F[Unit]] = unit
    }

  def empty[F[_]: Applicative, A]: Snapshotter[F, A] = {
    const(Instant.ofEpochMilli(0L).pure[F].pure[F], ().pure[F].pure[F])
  }

  private sealed abstract class Convert

  private sealed abstract class MapK

  private sealed abstract class WithFail

  private sealed abstract class WithLogging

  implicit class SnapshotterOps[F[_], A](val self: Snapshotter[F, A])
      extends AnyVal {

    def mapK[G[_]: Applicative](f: F ~> G): Snapshotter[G, A] = {
      new MapK with Snapshotter[G, A] {

        def save(seqNr: SeqNr, snapshot: A) =
          f(self.save(seqNr, snapshot)).map { a =>
            f(a)
          }

        def delete(seqNr: SeqNr) = f(self.delete(seqNr)).map { a =>
          f(a)
        }

        def delete(criteria: SnapshotSelectionCriteria) =
          delete(Criteria(criteria))

        def delete(criteria: Criteria): G[G[Unit]] =
          f(self.delete(criteria)).map { a =>
            f(a)
          }
      }
    }

    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Snapshotter[F, B] = {
      new Convert with Snapshotter[F, B] {

        def save(seqNr: SeqNr, snapshot: B) = f(snapshot).flatMap { a =>
          self.save(seqNr, a)
        }

        def delete(seqNr: SeqNr) = self.delete(seqNr)

        def delete(criteria: SnapshotSelectionCriteria) =
          delete(Criteria(criteria))

        def delete(criteria: Criteria): F[F[Unit]] = self.delete(criteria)
      }
    }

    def withLogging1(log: Log[F])(
      implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F]
    ): Snapshotter[F, A] = {
      new WithLogging with Snapshotter[F, A] {

        def save(seqNr: SeqNr, snapshot: A) = {
          for {
            d <- MeasureDuration[F].start
            r <- self.save(seqNr, snapshot)
          } yield
            for {
              r <- r
              d <- d
              _ <- log.info(s"save snapshot at $seqNr in ${d.toMillis}ms")
            } yield r
        }

        def delete(seqNr: SeqNr) = {
          for {
            d <- MeasureDuration[F].start
            r <- self.delete(seqNr)
          } yield
            for {
              r <- r
              d <- d
              _ <- log.info(s"delete snapshot at $seqNr in ${d.toMillis}ms")
            } yield r
        }

        def delete(criteria: SnapshotSelectionCriteria) = {
          delete(Criteria(criteria))
        }

        def delete(criteria: Criteria): F[F[Unit]] = {
          for {
            d <- MeasureDuration[F].start
            r <- self.delete(criteria)
          } yield
            for {
              r <- r
              d <- d
              _ <- log.info(
                s"delete snapshots for $criteria in ${d.toMillis}ms"
              )
            } yield r
        }

      }
    }

    def withFail(fail: Fail[F])(implicit
                                F: MonadThrowable[F]): Snapshotter[F, A] = {
      new WithFail with Snapshotter[F, A] {

        def save(seqNr: SeqNr, snapshot: A) = {
          fail.adapt(s"failed to save snapshot at $seqNr") {
            self.save(seqNr, snapshot)
          }
        }

        def delete(seqNr: SeqNr) = {
          fail.adapt(s"failed to delete snapshot at $seqNr") {
            self.delete(seqNr)
          }
        }

        def delete(criteria: SnapshotSelectionCriteria) = {
          delete(Criteria(criteria))
        }

        def delete(criteria: Criteria): F[F[Unit]] = {
          fail.adapt(s"failed to delete snapshots for $criteria") {
            self.delete(criteria)
          }
        }

      }
    }
  }
}
