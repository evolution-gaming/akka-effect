package com.evolutiongaming.akkaeffect.eventsourcing

import cats.implicits._

final case class Directive[F[_], +S, +E](
  change: Option[Change[S, E]],
  effect: Effect[F])

object Directive {
  
  def apply[F[_], S, E](effect: Effect[F]): Directive[F, S, E] = apply(none, effect)
}
