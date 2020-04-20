package com.evolutiongaming.akkaeffect.eventsourcing

import cats.Applicative
import cats.implicits._
import com.evolutiongaming.akkaeffect.persistence.SeqNr

trait Validate[F[_], S, E] {

  // TODO return directives including one for snapshots
  def apply(state: S, seqNr: SeqNr): F[Directive[F, S, E]]
}

object Validate {

  def const[F[_]: Applicative, S, E](directive: Directive[F, S, E]): Validate[F, S, E] = {
    (_: S, _: SeqNr) => directive.pure[F]
  }

  def empty[F[_]: Applicative, S, E]: Validate[F, S, E] = const(Directive.empty[F, S, E])
}