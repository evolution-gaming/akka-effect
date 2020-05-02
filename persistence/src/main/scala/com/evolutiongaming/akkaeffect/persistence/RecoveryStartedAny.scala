package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._

/**
  * Describes start of recovery phase
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

  def const[F[_], S, C, E](
    recovering: Resource[F, RecoveringAny[F, S, C, E]]
  ): RecoveryStartedAny[F, S, C, E] = {
    (_, _) => recovering
  }

  def empty[F[_]: Monad, S, C, E]: RecoveryStartedAny[F, S, C, E] = {
    const(RecoveringAny.empty[F, S, C, E].pure[Resource[F, *]])
  }


  implicit class RecoveryStartedOps[F[_], S, C, E](
    val self: RecoveryStartedAny[F, S, C, E]
  ) extends AnyVal {

    def convert[S1, C1, E1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E])(implicit
      F: Monad[F],
    ): RecoveryStartedAny[F, S1, C1, E1] = {

      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          s1f(snapshotOffer.snapshot).map { snapshot => snapshotOffer.as(snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.convert(sf, cf, ef, e1f)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F],
    ): RecoveryStartedAny[F, S1, C1, E1] = {
      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.widen(cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): RecoveryStartedAny[F, Any, Any, Any] = widen[Any, Any, Any](sf, cf, ef)
  }
}
