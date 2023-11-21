package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

trait Snapshot[S] {

  def snapshot: S
  def metadata: Snapshot.Metadata

}

object Snapshot {

  final case class Metadata(seqNr: SeqNr, timestamp: Instant)

}
