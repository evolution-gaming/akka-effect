package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.ActorCtx

trait PersistenceSetupOf[F[_], S, C, E, R] {

  // TODO Option
  def apply(ctx: ActorCtx[F, C, R]): F[PersistenceSetup[F, S, C, E, R]]
}

object PersistenceSetupOf {

  def const[F[_] : Applicative, S, C, E, R](
    persistenceSetup: PersistenceSetup[F, S, C, E, R]
  ): PersistenceSetupOf[F, S, C, E, R] = {
    _ => persistenceSetup.pure[F]
  }


  implicit class PersistenceSetupOfOps[F[_], S, C, E, R](
    val self: PersistenceSetupOf[F, S, C, E, R]
  ) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C => F[C1],
      c1f: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1],
      r1f: R1 => F[R],
    )(implicit
      F: Monad[F]
    ): PersistenceSetupOf[F, S1, C1, E1, R1] = {
      ctx: ActorCtx[F, C1, R1] => {
        val ctx1 = ctx.convert(cf, r1f)
        for {
          persistenceSetup <- self(ctx1)
        } yield {
          persistenceSetup.convert(sf, s1f, c1f, ef, e1f, rf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E],
      rf: Any => F[R])(implicit
      F: Monad[F],
    ): PersistenceSetupOf[F, S1, C1, E1, R1] = {
      ctx: ActorCtx[F, C1, R1] => {
        val ctx1 = ctx.narrow[C, R](rf)
        for {
          persistenceSetup <- self(ctx1)
        } yield {
          persistenceSetup.widen(sf, cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E],
      rf: Any => F[R])(implicit
      F: Monad[F],
    ): PersistenceSetupOf[F, Any, Any, Any, Any] = widen(sf, cf, ef, rf)
  }
}
