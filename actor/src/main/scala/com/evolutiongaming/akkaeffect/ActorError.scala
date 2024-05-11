package com.evolutiongaming.akkaeffect

import cats.syntax.all._

final case class ActorError(
  msg: String,
  cause: Option[Throwable] = None
) extends RuntimeException(msg, cause.orNull)

object ActorError {

  def apply(msg: String, cause: Throwable): ActorError = ActorError(msg, cause.some)
}
