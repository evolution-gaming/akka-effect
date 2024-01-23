package akka.persistence

import akka.actor.ActorSystem
import akka.persistence.SnapshotSelectionCriteria
import cats.MonadThrow
import cats.effect.Sync
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.EventSourcedId
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.akkaeffect.persistence.SnapshotStore
import com.evolutiongaming.catshelper.FromFuture

import java.time.Instant
import scala.concurrent.duration._

object SnapshotStoreInterop {

  def apply[F[_]: Sync: FromFuture, A](
    system: ActorSystem,
    timeout: FiniteDuration,
    snapshotPluginId: String,
    eventSourcedId: EventSourcedId
  ): F[SnapshotStore[F, A]] =
    Sync[F]
      .delay {
        val actorRef = Persistence(system).snapshotStoreFor(snapshotPluginId)
        ActorEffect.fromActor(actorRef)
      }
      .map { snapshotter =>
        new SnapshotStore[F, A] {

          val persistenceId = eventSourcedId.value

          override def latest: F[Option[SnapshotStore.Offer[A]]] = {
            val criteria = SnapshotSelectionCriteria()
            val request  = SnapshotProtocol.LoadSnapshot(persistenceId, criteria, Long.MaxValue)
            snapshotter
              .ask(request, timeout)
              .flatMap { response =>
                response.flatMap {

                  case SnapshotProtocol.LoadSnapshotResult(snapshot, _) =>
                    snapshot match {

                      case Some(offer) =>
                        val payload   = MonadThrow[F].catchNonFatal(offer.snapshot.asInstanceOf[A])
                        val timestamp = Instant.ofEpochMilli(offer.metadata.timestamp)
                        val metadata  = SnapshotStore.Metadata(offer.metadata.sequenceNr, timestamp)
                        payload.map(SnapshotStore.Offer(_, metadata).some)

                      case None => none[SnapshotStore.Offer[A]].pure[F]
                    }

                  case SnapshotProtocol.LoadSnapshotFailed(err) =>
                    err.raiseError[F, Option[SnapshotStore.Offer[A]]]
                }
              }
          }

          override def save(seqNr: SeqNr, snapshot: A): F[F[Instant]] = {
            val metadata = SnapshotMetadata(persistenceId, seqNr)
            val request  = SnapshotProtocol.SaveSnapshot(metadata, snapshot)
            snapshotter
              .ask(request, timeout)
              .map { response =>
                response.flatMap {
                  case SaveSnapshotSuccess(metadata) => Instant.ofEpochMilli(metadata.timestamp).pure[F]
                  case SaveSnapshotFailure(_, err)   => err.raiseError[F, Instant]
                }

              }
          }

          override def delete(seqNr: SeqNr): F[F[Unit]] = {
            val metadata = SnapshotMetadata(persistenceId, seqNr)
            val request  = SnapshotProtocol.DeleteSnapshot(metadata)
            snapshotter
              .ask(request, timeout)
              .map { response =>
                response.flatMap {
                  case DeleteSnapshotSuccess(_)      => ().pure[F]
                  case DeleteSnapshotFailure(_, err) => err.raiseError[F, Unit]
                }
              }
          }

          override def delete(criteria: SnapshotStore.Criteria): F[F[Unit]] = {
            val query = SnapshotSelectionCriteria(
              maxSequenceNr = criteria.maxSequenceNr,
              maxTimestamp = criteria.maxTimestamp,
              minSequenceNr = criteria.minSequenceNr,
              minTimestamp = criteria.minTimestamp
            )
            val request = SnapshotProtocol.DeleteSnapshots(persistenceId, query)
            snapshotter
              .ask(request, timeout)
              .map { response =>
                response.flatMap {
                  case DeleteSnapshotsSuccess(_)      => ().pure[F]
                  case DeleteSnapshotsFailure(_, err) => err.raiseError[F, Unit]
                }
              }
          }

        }
      }

}
