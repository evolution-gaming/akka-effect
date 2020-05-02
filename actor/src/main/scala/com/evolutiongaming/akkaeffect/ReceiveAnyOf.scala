package com.evolutiongaming.akkaeffect

import cats.effect.{Resource, Sync}

trait ReceiveAnyOf[F[_]] {

  def apply(actorCtx: ActorCtx[F]): Resource[F, ReceiveAny[F, Any]]
}

object ReceiveAnyOf {

  def apply[F[_], A](f: ActorCtx[F] => Resource[F, ReceiveAny[F, Any]]): ReceiveAnyOf[F] = a => f(a)


  def fromReceiveOf[F[_]: Sync](receiveOf: ReceiveOf[F, Any, Any]): ReceiveAnyOf[F] = {
    actorCtx => receiveOf(actorCtx).map { _.toReceiveAny(actorCtx.self) }
  }
}