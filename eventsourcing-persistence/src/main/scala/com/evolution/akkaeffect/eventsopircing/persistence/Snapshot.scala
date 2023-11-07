package com.evolution.akkaeffect.eventsopircing.persistence

import java.time.Instant

trait Snapshot[S] {

  def snapshot: S
  def metadata: Snapshot.Metadata

}

object Snapshot {

  final case class Metadata(seqNr: SeqNr, timestamp: Instant)

}
