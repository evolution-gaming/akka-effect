package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

/** @see
  *   [[akka.persistence.SnapshotMetadata]]
  */
final case class SnapshotMetadata(seqNr: SeqNr, timestamp: Instant)

object SnapshotMetadata {

  val Empty: SnapshotMetadata = SnapshotMetadata(seqNr = 1, timestamp = Instant.ofEpochMilli(0))

  def apply(metadata: akka.persistence.SnapshotMetadata): SnapshotMetadata =
    SnapshotMetadata(seqNr = metadata.sequenceNr, timestamp = Instant.ofEpochMilli(metadata.timestamp))
}
