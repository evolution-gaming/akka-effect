package com.evolutiongaming.akkaeffect.eventsourcing

import cats.syntax.all._


final case class EngineError(
  msg: String,
  cause: Option[Throwable] = None
) extends RuntimeException(msg, cause.orNull)

object EngineError {

  def apply(msg: String, cause: Throwable): EngineError = EngineError(msg, cause.some)
}


