package com.evolutiongaming.akkaeffect

import cats.effect.{Bracket, Resource}
import cats.implicits._
import cats.{Applicative, Defer, Monad, ~>}

/**
  * Factory method for [[Receive]]
  *
  * @tparam A message
  * @tparam B reply
  */
trait ReceiveOf[F[_], A, B] {

  def apply(ctx: ActorCtx[F, A, B]): Resource[F, Option[Receive[F, A, B]]]
}

object ReceiveOf {

  def const[F[_] : Applicative, A, B](receive: Option[Receive[F, A, B]]): ReceiveOf[F, A, B] = {
    _ => Resource.pure[F, Option[Receive[F, A, B]]](receive)
  }


  def empty[F[_] : Applicative, A, B]: ReceiveOf[F, A, B] = const(none[Receive[F, A, B]])


  implicit class ReceiveOfOps[F[_], A, B](val self: ReceiveOf[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A => F[A1],
      a1f: A1 => F[A],
      bf: B => F[B1],
      b1f: B1 => F[B])(implicit
      F: Monad[F],
    ): ReceiveOf[F, A1, B1] = new ReceiveOf[F, A1, B1] {

      def apply(ctx: ActorCtx[F, A1, B1]) = {
        val ctx1 = ctx.convert[A, B](af, b1f)
        for {
          receive <- self(ctx1)
        } yield for {
          receive <- receive
        } yield {
          receive.convert(a1f, bf)
        }
      }
    }


    def widen[A1 >: A, B1 >: B](
      fa: A1 => F[A],
      fb: B1 => F[B])(implicit
      F: Monad[F]
    ): ReceiveOf[F, A1, B1] = {
      ctx: ActorCtx[F, A1, B1] => {
        val ctx1 = ctx.narrow[A, B](fb)
        for {
          receive <- self(ctx1)
        } yield for {
          receive <- receive
        } yield {
          receive.widen(fa)
        }
      }
    }


    def typeless(
      fa: Any => F[A],
      fb: Any => F[B])(implicit
      F: Monad[F]
    ): ReceiveOf[F, Any, Any] = widen(fa, fb)


    def mapK[G[_]](
      fg: F ~> G,
      gf: G ~> F)(implicit
      F: Bracket[F, Throwable],
      FD: Defer[F],
      G: Bracket[G, Throwable],
      GD: Defer[G],
    ): ReceiveOf[G, A, B] = new ReceiveOf[G, A, B] {

      def apply(ctx: ActorCtx[G, A, B]) = {
        for {
          receive <- self(ctx.mapK(gf)).mapK(fg)
        } yield for {
          receive <- receive
        } yield {
          receive.mapK(fg, gf)
        }
      }
    }
  }
}
