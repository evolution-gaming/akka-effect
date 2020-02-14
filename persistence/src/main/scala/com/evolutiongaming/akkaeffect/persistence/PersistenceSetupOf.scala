package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.{ActorCtx, Convert}

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

    def convert(implicit
      F: Monad[F],
      anyS: Convert[F, Any, S],
      anyC: Convert[F, Any, C],
      anyE: Convert[F, Any, E],
      anyR: Convert[F, Any, R]
    ): PersistenceSetupOf[F, Any, Any, Any, Any] = {

      ctx: ActorCtx[F, Any, Any] => self(ctx.convert[C, R]).map { _.untyped }
    }
  }
}
