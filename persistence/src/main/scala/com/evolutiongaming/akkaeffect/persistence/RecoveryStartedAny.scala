package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource

/**
  * Describes "Started" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  */
trait RecoveryStartedAny[F[_], S, C, E] {

  /**
    * Called upon starting recovery, resource will be released upon actor termination
    *
    * @see [[akka.persistence.SnapshotOffer]]
    */
  def apply(
    seqNr: SeqNr,
    snapshotOffer: Option[SnapshotOffer[S]]
  ): Resource[F, RecoveringAny[F, S, C, E]]
}

object RecoveryStartedAny {

  def apply[F[_], S, C, E](
    f: (SeqNr, Option[SnapshotOffer[S]]) => Resource[F, RecoveringAny[F, S, C, E]]
  ): RecoveryStartedAny[F, S, C, E] = {
    (seqNr, snapshotOffer) => f(seqNr, snapshotOffer)
  }
}
