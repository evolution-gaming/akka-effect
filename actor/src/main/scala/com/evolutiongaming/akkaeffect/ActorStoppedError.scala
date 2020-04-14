package com.evolutiongaming.akkaeffect

import cats.implicits._


final case class ActorStoppedError(
  msg: String,
  cause: Option[Throwable] = None
) extends RuntimeException(msg, cause.orNull)

object ActorStoppedError {

  def apply(msg: String, cause: Throwable): ActorStoppedError = ActorStoppedError(msg, cause.some)
}
