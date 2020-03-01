package com.evolutiongaming.akkaeffect.eventsourcing

import com.evolutiongaming.akkaeffect.persistence.SeqNr

trait Effect[F[_]] {
  
  def apply(seqNr: Either[Throwable, SeqNr]): F[Unit]
}
