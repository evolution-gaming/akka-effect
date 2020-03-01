package com.evolutiongaming.akkaeffect.eventsourcing

import com.evolutiongaming.akkaeffect.persistence.SeqNr

trait Journaller[F[_]] {
  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}