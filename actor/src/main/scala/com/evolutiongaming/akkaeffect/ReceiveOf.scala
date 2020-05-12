package com.evolutiongaming.akkaeffect

import cats.effect.{Resource, Sync}

trait ReceiveOf[F[_]] {

  def apply(actorCtx: ActorCtx[F]): Resource[F, Receive[F, Any]]
}

object ReceiveOf {

  def apply[F[_]](f: ActorCtx[F] => Resource[F, Receive[F, Any]]): ReceiveOf[F] = a => f(a)


  def fromReceiveOf[F[_]: Sync](receiveOf: Receive1Of[F, Any, Any]): ReceiveOf[F] = {
    actorCtx => receiveOf(actorCtx).map { _.toReceiveAny(actorCtx.self) }
  }
}