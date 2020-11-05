package akka.persistence

import java.time.Instant

import akka.persistence.SnapshotProtocol.{DeleteSnapshot, DeleteSnapshots, Request, SaveSnapshot}
import akka.util.Timeout
import cats.effect.Sync
import cats.syntax.all._
import com.evolutiongaming.akkaeffect
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

object SnapshotterInterop {

  def apply[F[_] : Sync : FromFuture, A](
    snapshotter: Snapshotter,
    timeout: FiniteDuration
  ): akkaeffect.persistence.Snapshotter[F, A] = {

    val timeout1 = Timeout(timeout)

    def snapshotterId = snapshotter.snapshotterId

    def snapshotStore = snapshotter.snapshotStore

    def metadata(seqNr: SeqNr) = SnapshotMetadata(snapshotterId, seqNr)

    def ask[B](a: Request)(pf: PartialFunction[Any, F[B]]): F[F[B]] = {
      Sync[F]
        .delay { akka.pattern.ask(snapshotStore, a, snapshotter.self)(timeout1) }
        .map { future =>
          FromFuture
            .summon[F]
            .apply { future }
            .flatMap { a =>
              Sync[F]
                .catchNonFatal { pf(a) }
                .flatten
            }
        }
    }

    new akkaeffect.persistence.Snapshotter[F, A] {

      def save(seqNr: SeqNr, snapshot: A) = {
        ask(SaveSnapshot(metadata(seqNr), snapshot)) {
          case a: SaveSnapshotSuccess => Instant.ofEpochMilli(a.metadata.timestamp).pure[F]
          case a: SaveSnapshotFailure => a.cause.raiseError[F, Instant]
        }
      }

      def delete(seqNr: SeqNr) = {
        ask(DeleteSnapshot(metadata(seqNr))) {
          case _: DeleteSnapshotSuccess => ().pure[F]
          case a: DeleteSnapshotFailure => a.cause.raiseError[F, Unit]
        }
      }

      def delete(criteria: SnapshotSelectionCriteria) = {
        ask(DeleteSnapshots(snapshotterId, criteria)) {
          case _: DeleteSnapshotsSuccess => ().pure[F]
          case a: DeleteSnapshotsFailure => a.cause.raiseError[F, Unit]
        }
      }
    }
  }
}
