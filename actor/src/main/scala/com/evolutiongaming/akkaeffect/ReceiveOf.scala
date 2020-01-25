package com.evolutiongaming.akkaeffect

import cats.Applicative
import cats.effect.Resource
import cats.implicits._

trait ReceiveOf[F[_], A, B] {

  def apply(ctx: ActorCtx[F, A, B]): Resource[F, Option[Receive[F, A, B]]]
}

object ReceiveOf {

  def const[F[_] : Applicative, A, B](receive: Option[Receive[F, A, B]]): ReceiveOf[F, A, B] = {
    _ => Resource.pure[F, Option[Receive[F, A, B]]](receive)
  }

  def empty[F[_] : Applicative, A, B]: ReceiveOf[F, A, B] = const(none[Receive[F, A, B]])
}
