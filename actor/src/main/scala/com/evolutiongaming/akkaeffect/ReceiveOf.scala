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

  def apply(actorCtx: ActorCtx[F]): Resource[F, Option[Receive[F, A, B]]]
}

object ReceiveOf {

  def const[F[_]: Applicative, A, B](receive: Option[Receive[F, A, B]]): ReceiveOf[F, A, B] = {
    _ => Resource.pure[F, Option[Receive[F, A, B]]](receive)
  }


  def empty[F[_]: Applicative, A, B]: ReceiveOf[F, A, B] = const(none[Receive[F, A, B]])


  def apply[F[_], A, B](
    f: ActorCtx[F] => Resource[F, Option[Receive[F, A, B]]]
  ): ReceiveOf[F, A, B] = {
    a => f(a)
  }


  implicit class ReceiveOfOps[F[_], A, B](val self: ReceiveOf[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: Monad[F],
    ): ReceiveOf[F, A1, B1] = {
      actorCtx: ActorCtx[F] => {
        for {
          receive <- self(actorCtx)
        } yield for {
          receive <- receive
        } yield {
          receive.convert(af, bf)
        }
      }
    }


    def widen[A1 >: A, B1 >: B](
      f: A1 => F[A])(implicit
      F: Monad[F]
    ): ReceiveOf[F, A1, B1] = {
      actorCtx: ActorCtx[F] => {
        for {
          receive <- self(actorCtx)
        } yield for {
          receive <- receive
        } yield {
          receive.widen(f)
        }
      }
    }


    def typeless(f: Any => F[A])(implicit F: Monad[F]): ReceiveOf[F, Any, Any] = widen(f)


    def mapK[G[_]](
      fg: F ~> G,
      gf: G ~> F)(implicit
      F: Sync[F],
      G: Sync[G],
    ): ReceiveOf[G, A, B] = {
      actorCtx: ActorCtx[G] => {
        for {
          receive <- self(actorCtx.mapK(gf)).mapK(fg)
        } yield for {
          receive <- receive
        } yield {
          receive.mapK(fg, gf)
        }
      }
    }
  }


  implicit class ReceiveAnyOfOps[F[_]](val self: ReceiveOf[F, Any, Any]) extends AnyVal {

    def toReceiveAnyOf(implicit F: Sync[F]): ReceiveAnyOf[F] = ReceiveAnyOf.fromReceiveOf(self)
  }
}
