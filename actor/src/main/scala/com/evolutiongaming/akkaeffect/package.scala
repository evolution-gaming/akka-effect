package com.evolutiongaming

import cats.effect.Resource

package object akkaeffect {

  // TODO use interface
  type ReceiveOf[F[_], A, B] = ActorCtx[F, Any, Any] => Resource[F, Option[Receive[F, Any, Any]]]
}
