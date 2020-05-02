package com.evolutiongaming.akkaeffect

import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.{Applicative, Defer, Monad, ~>}

/**
  * Factory method for [[Receive1]]
  *
  * @tparam A message
  * @tparam B reply
  */
trait Receive1Of[F[_], A, B] {

  def apply(actorCtx: ActorCtx[F]): Resource[F, Receive1[F, A, B]]
}

object Receive1Of {

  def const[F[_], A, B](receive: Resource[F, Receive1[F, A, B]]): Receive1Of[F, A, B] = _ => receive

  def empty[F[_]: Monad, A, B]: Receive1Of[F, A, B] = const(Receive1.empty[F, A, B].pure[Resource[F, *]])

  def apply[F[_], A, B](f: ActorCtx[F] => Resource[F, Receive1[F, A, B]]): Receive1Of[F, A, B] = {
    actorCtx => f(actorCtx)
  }


  implicit class ReceiveOfOps[F[_], A, B](val self: Receive1Of[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: Monad[F],
    ): Receive1Of[F, A1, B1] = {
      actorCtx => self(actorCtx).map { _.convert(af, bf) }
    }


    def widen[A1 >: A, B1 >: B](
      f: A1 => F[A])(implicit
      F: Monad[F]
    ): Receive1Of[F, A1, B1] = {
      actorCtx => self(actorCtx).map { _.widen(f) }
    }


    def typeless(f: Any => F[A])(implicit F: Monad[F]): Receive1Of[F, Any, Any] = widen(f)


    def mapK[G[_]](
      fg: F ~> G,
      gf: G ~> F)(implicit
      D: Defer[G],
      G: Applicative[G]
    ): Receive1Of[G, A, B] = {
      actorCtx =>
        self(actorCtx.mapK(gf))
          .mapK(fg)
          .map { _.mapK(fg, gf) }
    }
  }


  implicit class ReceiveAnyOfOps[F[_]](val self: Receive1Of[F, Any, Any]) extends AnyVal {

    def toReceiveOf(implicit F: Sync[F]): ReceiveOf[F] = ReceiveOf.fromReceiveOf(self)
  }
}
