package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._

/**
  * Describes start of recovery phase
  *
  * @tparam S snapshot
  * @tparam E event
  * @tparam C command
  */
trait RecoveryStarted[F[_], S, E, C] {

  /**
    * Called upon starting recovery, resource will be released upon actor termination
    *
    * @see [[akka.persistence.SnapshotOffer]]
    */
  def apply(
    seqNr: SeqNr,
    snapshotOffer: Option[SnapshotOffer[S]]
  ): Resource[F, Recovering[F, S, E, C]]
}

object RecoveryStarted {

  def apply[F[_], S, E, C](
    f: (SeqNr, Option[SnapshotOffer[S]]) => Resource[F, Recovering[F, S, E, C]]
  ): RecoveryStarted[F, S, E, C] = {
    (seqNr, snapshotOffer) => f(seqNr, snapshotOffer)
  }

  def const[F[_], S, E, C](
    recovering: Resource[F, Recovering[F, S, E, C]]
  ): RecoveryStarted[F, S, E, C] = {
    (_, _) => recovering
  }

  def empty[F[_]: Monad, S, E, C]: RecoveryStarted[F, S, E, C] = {
    const(Recovering.empty[F, S, E, C].pure[Resource[F, *]])
  }


  implicit class RecoveryStartedOps[F[_], S, E, C](
    val self: RecoveryStarted[F, S, E, C]
  ) extends AnyVal {

    def convert[S1, E1, C1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, S1, E1, C1] = {

      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          s1f(snapshotOffer.snapshot).map { snapshot => snapshotOffer.as(snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.convert(sf, ef, e1f, cf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: S1 => F[S],
      ef: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, S1, E1, C1] = {
      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.widen(ef, cf)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, Any, Any, Any] = widen[Any, Any, Any](sf, ef, cf)
  }
}
