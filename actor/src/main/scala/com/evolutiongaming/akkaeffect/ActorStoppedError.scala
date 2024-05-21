package com.evolutiongaming.akkaeffect

import cats.syntax.all.*

import scala.util.control.NoStackTrace

final case class ActorStoppedError(
  msg: String,
  cause: Option[Throwable] = None,
) extends RuntimeException(msg, cause.orNull)
    with NoStackTrace

object ActorStoppedError {

  def apply(msg: String, cause: Throwable): ActorStoppedError = ActorStoppedError(msg, cause.some)
}
