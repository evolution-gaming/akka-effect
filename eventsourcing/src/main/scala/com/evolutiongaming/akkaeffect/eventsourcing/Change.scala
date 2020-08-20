package com.evolutiongaming.akkaeffect.eventsourcing

import cats.Semigroup
import cats.implicits._
import com.evolutiongaming.akkaeffect.persistence.Events

/**
  * Used to provide new state to be captured and events to be stored
  *
  * @param state  - state after applied events
  * @param events - inner Nel of events will be saved atomically
  * @tparam S state
  * @tparam E event
  */
final case class Change[+S, +E](state: S, events: Events[E])

object Change {

  def apply[S, E](state: S, event: E): Change[S, E] = apply(state, Events.of(event))


  implicit def semigroupChange[S, E]: Semigroup[Change[S, E]] = {
    (a, b) => Change(b.state, a.events.combine(b.events))
  }
}