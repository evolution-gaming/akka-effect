package com.evolutiongaming.akkaeffect.eventsourcing

import cats.Applicative
import cats.implicits._

/**
  * Describes optional change as well as effect to be executed after change is applied and events are stored
  *
  * @param change - state and events
  * @param effect - will be executed after events are stored
  * @tparam S state
  * @tparam E event
  */
final case class Directive[F[_], +S, +E](
  change: Option[Change[S, E]],
  effect: Effect[F])

object Directive {

  def empty[F[_]: Applicative, S, E]: Directive[F, S, E] = Directive(none[Change[S, E]], Effect.empty[F])

  def apply[F[_], S, E](effect: Effect[F]): Directive[F, S, E] = apply(none, effect)
}
