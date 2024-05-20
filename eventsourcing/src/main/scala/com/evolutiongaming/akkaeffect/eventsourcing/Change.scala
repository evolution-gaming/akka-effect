package com.evolutiongaming.akkaeffect.eventsourcing

import cats.syntax.all.*
import cats.{Functor, Monad, Semigroup}
import com.evolutiongaming.akkaeffect.persistence.Events

/** Used to provide new state to be captured and events to be stored
  *
  * @param state
  *   \- state after applied events
  * @param events
  *   \- inner Nel of events will be saved atomically
  * @tparam S
  *   state
  * @tparam E
  *   event
  */
final case class Change[+S, +E](state: S, events: Events[E]) {
  def mapState[S1](f: S => S1): Change[S1, E] = Change(f(state), events)

  def mapEvent[E1](f: E => E1): Change[S, E1] = Change(state, events.map(f))
}

object Change {

  def apply[S, E](state: S, event: E): Change[S, E] = apply(state, Events.of(event))

  implicit def semigroupChange[S, E]: Semigroup[Change[S, E]] = { (a, b) =>
    Change(b.state, a.events.combine(b.events))
  }

  implicit class ChangeOps[S, E](val self: Change[S, E]) extends AnyVal {

    def convert[F[_], S1, E1](sf: S => F[S1], ef: E => F[E1])(implicit F: Monad[F]): F[Change[S1, E1]] =
      for {
        state  <- sf(self.state)
        events <- self.events.traverse(ef)
      } yield Change(state, events)

    def convertE[F[_], E1](f: E => F[E1])(implicit F: Monad[F]): F[Change[S, E1]] =
      self.events.traverse(f).map(events => self.copy(events = events))

    def convertS[F[_], S1](f: S => F[S1])(implicit F: Functor[F]): F[Change[S1, E]] =
      f(self.state).map(state => self.copy(state = state))
  }
}
