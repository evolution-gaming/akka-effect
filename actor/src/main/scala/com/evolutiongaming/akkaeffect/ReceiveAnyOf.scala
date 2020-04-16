package com.evolutiongaming.akkaeffect

import cats.effect.{Resource, Sync}

trait ReceiveAnyOf[F[_]] {

  def apply(actorCtx: ActorCtx[F]): Resource[F, Option[ReceiveAny[F]]]
}

object ReceiveAnyOf {

  def fromReceiveOf[F[_]: Sync](receiveOf: ReceiveOf[F, Any, Any]): ReceiveAnyOf[F] = {
    actorCtx: ActorCtx[F] => {
      receiveOf(actorCtx).map { _.map { _.toReceiveAny(actorCtx.self) } }
    }
  }
}