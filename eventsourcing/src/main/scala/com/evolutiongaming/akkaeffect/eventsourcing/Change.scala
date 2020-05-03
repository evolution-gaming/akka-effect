package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}

/**
  * Used to provide new state to be captured and events to be stored
  *
  * @param state  - state after applied events
  * @param events - inner Nel of events will be saved atomically
  * @tparam S state
  * @tparam E event
  */
final case class Change[+S, +E](
  state: S,
  events: Nel[Nel[E]])