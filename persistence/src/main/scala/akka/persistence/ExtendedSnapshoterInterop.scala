package akka.persistence

import akka.actor.ActorSystem

import cats.syntax.all._
import cats.effect.Sync

import com.evolutiongaming.catshelper.FromFuture
import com.evolutiongaming.akkaeffect.ActorEffect
import com.evolutiongaming.akkaeffect.persistence.{ExtendedSnapshotter, EventSourcedId, SeqNr, Snapshot}

import scala.concurrent.duration._
import java.time.Instant

object ExtendedSnapshoterInterop {

  def apply[F[_]: Sync: FromFuture](
    system: ActorSystem,
    timeout: FiniteDuration
  ): F[ExtendedSnapshotter.Of[F]] =
    Sync[F]
      .delay {
        Persistence(system)
      }
      .map { persistence =>
        new ExtendedSnapshotter.Of[F] {

          override def apply[S](snapshotPluginId: String, eventSourcedId: EventSourcedId): F[ExtendedSnapshotter[F, S]] =
            Sync[F]
              .delay {
                val ref = persistence.snapshotStoreFor(snapshotPluginId)
                ActorEffect.fromActor(ref)
              }
              .map { actor =>
                new ExtendedSnapshotter[F, S] {

                  val persistenceId = eventSourcedId.value

                  override def load(criteria: SnapshotSelectionCriteria, toSequenceNr: SeqNr): F[F[Option[Snapshot[S]]]] = {

                    val request = SnapshotProtocol.LoadSnapshot(persistenceId, criteria, toSequenceNr)
                    actor
                      .ask(request, timeout)
                      .map { response =>
                        response.flatMap {

                          case SnapshotProtocol.LoadSnapshotResult(snapshot, _) =>
                            snapshot match {

                              case Some(offer) =>
                                val payload = offer.snapshot.asInstanceOf[S]
                                val metadata = Snapshot.Metadata(
                                  offer.metadata.sequenceNr,
                                  Instant.ofEpochMilli(offer.metadata.timestamp)
                                )
                                Snapshot.const(payload, metadata).some.pure[F]

                              case None => none[Snapshot[S]].pure[F]
                            }

                          case SnapshotProtocol.LoadSnapshotFailed(err) =>
                            err.raiseError[F, Option[Snapshot[S]]]
                        }
                      }
                  }

                  override def save(seqNr: SeqNr, snapshot: S): F[F[Instant]] = {
                    val metadata = SnapshotMetadata(persistenceId, seqNr)
                    val request  = SnapshotProtocol.SaveSnapshot(metadata, snapshot)
                    actor
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
                    actor
                      .ask(request, timeout)
                      .map { response =>
                        response.flatMap {
                          case DeleteSnapshotSuccess(_)      => ().pure[F]
                          case DeleteSnapshotFailure(_, err) => err.raiseError[F, Unit]
                        }
                      }
                  }

                  override def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]] = {
                    val request = SnapshotProtocol.DeleteSnapshots(persistenceId, criteria)
                    actor
                      .ask(request, timeout)
                      .map { response =>
                        response.flatMap {
                          case DeleteSnapshotsSuccess(_)      => ().pure[F]
                          case DeleteSnapshotsFailure(_, err) => err.raiseError[F, Unit]
                        }
                      }
                  }

                  override def delete(criteria: com.evolutiongaming.akkaeffect.persistence.Snapshotter.Criteria): F[F[Unit]] =
                    delete(criteria.asAkka)

                }
              }

        }

      }
}
