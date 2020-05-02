package com.evolutiongaming.akkaeffect

import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.{Applicative, Monad, ~>}

/**
  * Factory method for [[Receive]]
  *
  * @tparam A message
  * @tparam B reply
  */
trait ReceiveOf[F[_], A, B] {

  def apply(actorCtx: ActorCtx[F]): Resource[F, Receive[F, A, B]]
}

object ReceiveOf {

  def const[F[_], A, B](receive: Resource[F, Receive[F, A, B]]): ReceiveOf[F, A, B] = _ => receive

  def empty[F[_]: Monad, A, B]: ReceiveOf[F, A, B] = const(Receive.empty[F, A, B].pure[Resource[F, *]])

  def apply[F[_], A, B](f: ActorCtx[F] => Resource[F, Receive[F, A, B]]): ReceiveOf[F, A, B] = {
    actorCtx => f(actorCtx)
  }


  implicit class ReceiveOfOps[F[_], A, B](val self: ReceiveOf[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: Monad[F],
    ): ReceiveOf[F, A1, B1] = {
      actorCtx => self(actorCtx).map { _.convert(af, bf) }
    }


    def widen[A1 >: A, B1 >: B](
      f: A1 => F[A])(implicit
      F: Monad[F]
    ): ReceiveOf[F, A1, B1] = {
      actorCtx => self(actorCtx).map { _.widen(f) }
    }


    def typeless(f: Any => F[A])(implicit F: Monad[F]): ReceiveOf[F, Any, Any] = widen(f)


    def mapK[G[_]](
      fg: F ~> G,
      gf: G ~> F)(implicit
      F: Sync[F],
      G: Sync[G],
    ): ReceiveOf[G, A, B] = {
      actorCtx =>
        self(actorCtx.mapK(gf))
          .mapK(fg)
          .map { _.mapK(fg, gf) }
    }
  }


  implicit class ReceiveAnyOfOps[F[_]](val self: ReceiveOf[F, Any, Any]) extends AnyVal {

    def toReceiveAnyOf(implicit F: Sync[F]): ReceiveAnyOf[F] = ReceiveAnyOf.fromReceiveOf(self)
  }
}
