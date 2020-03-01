package com.evolutiongaming.akkaeffect.eventsourcing

import com.evolutiongaming.akkaeffect.persistence.SeqNr

trait Validate[F[_], S, E] {

  // TODO return directives including one for snapshots
  def apply(state: S, seqNr: SeqNr): F[Directive[F, S, E]]
}
