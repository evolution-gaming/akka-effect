package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._

/**
  * Describes "Started" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  * @tparam R reply
  */
trait RecoveryStartedAny[F[_], S, C, E, R] {

  /**
    * Called upon starting recovery, resource will be released upon actor termination
    *
    * @see [[akka.persistence.SnapshotOffer]]
    * @return None to stop actor, Some to continue
    */
  def apply(
    seqNr: SeqNr,
    snapshotOffer: Option[SnapshotOffer[S]]
  ): Resource[F, Option[RecoveringAny[F, S, C, E, R]]]
}

object RecoveryStartedAny {

  def apply[F[_], S, C, E, R](
    f: (SeqNr, Option[SnapshotOffer[S]]) => Resource[F, Option[RecoveringAny[F, S, C, E, R]]]
  ): RecoveryStartedAny[F, S, C, E, R] = {
    (seqNr, snapshotOffer) => f(seqNr, snapshotOffer)
  }
}
