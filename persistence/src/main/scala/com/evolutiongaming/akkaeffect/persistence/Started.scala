package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._

trait Started[F[_], S, C, E, R] {

  /**
    * Called upon starting recovery, resource will be released upon actor termination
    *
    * @see [[akka.persistence.SnapshotOffer]]
    * @return None to stop actor, Some to continue
    */
  def recoveryStarted(snapshotOffer: Option[SnapshotOffer[S]]): Resource[F, Option[Recovering[F, S, C, E, R]]]
}

object Started {

  implicit class StartedOps[F[_], S, C, E, R](val self: Started[F, S, C, E, R]) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1])(implicit
      F: Monad[F],
    ): Started[F, S1, C1, E1, R1] = {

      snapshotOffer: Option[SnapshotOffer[S1]] => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          s1f(snapshotOffer.snapshot).map { snapshot => snapshotOffer.as(snapshot) }
        }

        for {
          snapshotOffer <- Resource.liftF(snapshotOffer1)
          recovering    <- self.recoveryStarted(snapshotOffer)
        } yield for {
          recovering <- recovering
        } yield {
          recovering.convert(sf, s1f, cf, ef, e1f, rf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F],
    ): Started[F, S1, C1, E1, R1] = {
      snapshotOffer: Option[SnapshotOffer[S1]] => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot) }
        }

        for {
          snapshotOffer <- Resource.liftF(snapshotOffer1)
          recovering    <- self.recoveryStarted(snapshotOffer)
        } yield for {
          recovering <- recovering
        } yield {
          recovering.widen(sf, cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): Started[F, Any, Any, Any, Any] = widen[Any, Any, Any, Any](sf, cf, ef)
  }
}
