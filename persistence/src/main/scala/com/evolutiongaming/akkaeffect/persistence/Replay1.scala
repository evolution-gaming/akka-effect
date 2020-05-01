package com.evolutiongaming.akkaeffect.persistence

trait Replay1[F[_], E] {

  def apply(seqNr: SeqNr, event: E): F[Unit]
}

object Replay1 {

  def apply[F[_], E](f: (SeqNr, E) => F[Unit]): Replay1[F, E] = {
    (seqNr, event) => f(seqNr, event)
  }
}
