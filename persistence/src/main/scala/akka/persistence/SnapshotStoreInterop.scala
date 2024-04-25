package akka.persistence

import java.time.Instant

import akka.persistence.SnapshotSelectionCriteria
import cats.effect.Sync
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, SeqNr, SnapshotStore}
import com.evolutiongaming.catshelper.{FromFuture, LogOf}

import scala.concurrent.duration._

object SnapshotStoreInterop {

  def apply[F[_]: Sync: FromFuture: LogOf](
    persistence: Persistence,
    timeout: FiniteDuration,
    snapshotPluginId: String,
    eventSourcedId: EventSourcedId
  ): F[SnapshotStore[F, Any]] =
    for {
      log <- LogOf.log[F, SnapshotStoreInterop.type]
      log <- log.prefixed(eventSourcedId.value).pure[F]
      snapshotter <- Sync[F]
        .delay {
          val actorRef = persistence.snapshotStoreFor(snapshotPluginId)
          ActorEffect.fromActor(actorRef)
        }
    } yield new SnapshotStore[F, Any] {

      val persistenceId = eventSourcedId.value

      override def latest: F[Option[SnapshotStore.Offer[Any]]] = {
        val criteria = SnapshotSelectionCriteria()
        val request  = SnapshotProtocol.LoadSnapshot(persistenceId, criteria, Long.MaxValue)
        val offer = snapshotter
          .ask(request, timeout)
          .flatMap { response =>
            response.flatMap {

              case SnapshotProtocol.LoadSnapshotResult(snapshot, _) =>
                snapshot match {

                  case Some(offer) =>
                    val payload   = offer.snapshot
                    val timestamp = Instant.ofEpochMilli(offer.metadata.timestamp)
                    val metadata  = SnapshotStore.Metadata(offer.metadata.sequenceNr, timestamp)

                    for {
                      _ <- log.debug(s"recovery: receive offer $offer")
                    } yield SnapshotStore.Offer(payload, metadata).some

                  case None => none[SnapshotStore.Offer[Any]].pure[F]
                }

              case SnapshotProtocol.LoadSnapshotFailed(err) =>
                for {
                  _ <- log.error(s"loading snapshot failed", err)
                  a <- err.raiseError[F, Option[SnapshotStore.Offer[Any]]]
                } yield a
            }
          }
        log.debug("recovery: snapshot requested") >> offer
      }

      override def save(seqNr: SeqNr, snapshot: Any): F[F[Instant]] = {
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
