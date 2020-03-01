package com.evolutiongaming.akkaeffect.eventsourcing

import com.evolutiongaming.akkaeffect.persistence.SeqNr

trait Validate[F[_], S, C, E] {

  // TODO return directives including one for snapshots
  def apply(state: S, seqNr: SeqNr): F[CmdResult.Change[F, S, E]]
}
