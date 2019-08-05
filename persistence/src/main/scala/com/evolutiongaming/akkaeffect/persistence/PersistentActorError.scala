package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._

final case class PersistentActorError(
  msg: String,
  cause: Option[Throwable] = None
) extends RuntimeException(msg, cause.orNull)

object PersistentActorError {

  def apply(msg: String, cause: Throwable): PersistentActorError = PersistentActorError(msg, cause.some)
}
