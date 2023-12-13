package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

trait SnapshotStore[F[_], A] extends SnapshotStore.Read[F, A] with SnapshotStore.Write[F, A]

object SnapshotStore {

  trait Read[F[_], A] {
    def latest: F[Option[SnapshotStore.Offer[A]]]
  }

  trait Write[F[_], -A] {
    def save(seqNr: SeqNr, snapshot: A): F[F[Instant]]
    def delete(seqNr: SeqNr): F[F[Unit]]
    def delete(criteria: Criteria): F[F[Unit]]
  }

  final case class Metadata(seqNr: SeqNr, timestamp: Instant)

  final case class Offer[A](snapshot: A, metadata: Metadata)

  final case class Criteria(
    maxSequenceNr: Long = Long.MaxValue,
    maxTimestamp: Long = Long.MaxValue,
    minSequenceNr: Long = 0L,
    minTimestamp: Long = 0L
  )
}
