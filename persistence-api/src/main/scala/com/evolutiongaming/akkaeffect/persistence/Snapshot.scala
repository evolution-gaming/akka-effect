package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

trait Snapshot[S] {

  def snapshot: S
  def metadata: Snapshot.Metadata

}

object Snapshot {

  final case class Metadata(seqNr: SeqNr, timestamp: Instant)

  def const[S](_snapshot: S, _metadata: Snapshot.Metadata): Snapshot[S] =
    new Snapshot[S] {

      override def snapshot: S = _snapshot

      override def metadata: Metadata = _metadata
    }
}
