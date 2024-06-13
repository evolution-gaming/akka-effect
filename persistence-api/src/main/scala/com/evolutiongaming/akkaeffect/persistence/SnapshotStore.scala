package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

/** Persistent snapshot-store API used in event-sourced actors [[EventSourcedActorOf]]. The API consists of two parts:
  * [[SnapshotStore.Read]] and [[SnapshotStore.Write]] that represents looking up and persisting snapshots.
  */
trait SnapshotStore[F[_], A] extends SnapshotStore.Read[F, A] with SnapshotStore.Write[F, A]

object SnapshotStore {

  trait Read[F[_], A] {

    /** Get latest snapshot if any
      *
      * @return
      *   snapshot offer if any
      */
    def latest: F[Option[SnapshotStore.Offer[A]]]
  }

  trait Write[F[_], -A] {

    /** Persist snapshot that represents state also achievable by applying events (on empty state) till sequence number.
      *
      * @param seqNr
      *   sequence number of last event used to recover state equal to snapshot
      * @param snapshot
      *   the snapshot to be persisted
      * @return
      *   outer F represents request send while inner F represents request complete
      */
    def save(seqNr: SeqNr, snapshot: A): F[F[Instant]]

    /** Delete snapshots till the sequence number (including)
      *
      * @param seqNr
      *   till which snapshots will be deleted
      * @return
      *   outer F represents request send while inner F represents request complete
      */
    def delete(seqNr: SeqNr): F[F[Unit]]

    /** Delete snapshots that satisfy criteria
      *
      * @param criteria
      *   criteria of deletion
      * @return
      *   outer F represents request send while inner F represents request complete
      */
    def delete(criteria: Criteria): F[F[Unit]]
  }

  final case class Metadata(seqNr: SeqNr, timestamp: Instant, persisted: Boolean)

  final case class Offer[A](snapshot: A, metadata: Metadata)

  final case class Criteria(
    maxSequenceNr: Long = Long.MaxValue,
    maxTimestamp: Long = Long.MaxValue,
    minSequenceNr: Long = 0L,
    minTimestamp: Long = 0L,
  )
}
