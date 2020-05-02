package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.Monad
import cats.effect.Resource

/**
  * EventSourced describes lifecycle of entity with regards to event sourcing
  * Lifecycle phases:
  *
  * 1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery
  * 2. Recovering     : reading snapshot and replaying events
  * 3. Receiving      : receiving commands and potentially storing events & snapshots
  * 4. Termination    : triggers all release hooks of allocated resources within previous phases
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  */
trait EventSourcedAny[F[_], S, C, E] {

  /**
    * @see [[akka.persistence.PersistentActor.persistenceId]]
    */
  def eventSourcedId: EventSourcedId

  /**
    * @see [[akka.persistence.PersistentActor.recovery]]
    */
  def recovery: Recovery

  /**
    * @see [[akka.persistence.PersistentActor.journalPluginId]]
    * @see [[akka.persistence.PersistentActor.snapshotPluginId]]
    */
  def pluginIds: PluginIds

  /**
    * Called just after actor is started, resource will be released upon actor termination
    *
    * @see [[akka.persistence.PersistentActor.preStart]]
    */
  def start: Resource[F, RecoveryStartedAny[F, S, C, E]]
}


object EventSourcedAny {

  implicit class EventSourcedOps[F[_], S, C, E](
    val self: EventSourcedAny[F, S, C, E]
  ) extends AnyVal {

    def convert[S1, C1, E1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E])(implicit
      F: Monad[F],
    ): EventSourcedAny[F, S1, C1, E1] = new EventSourcedAny[F, S1, C1, E1] {

      def eventSourcedId = self.eventSourcedId

      def pluginIds = self.pluginIds

      def recovery = self.recovery

      def start = {
        self.start.map { _.convert(sf, s1f, cf, ef, e1f) }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F],
    ): EventSourcedAny[F, S1, C1, E1] = new EventSourcedAny[F, S1, C1, E1] {

      def eventSourcedId = self.eventSourcedId

      def pluginIds = self.pluginIds

      def recovery = self.recovery

      def start = {
        self.start.map { _.widen(sf, cf, ef) }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourcedAny[F, Any, Any, Any] = {
      widen[Any, Any, Any](sf, cf, ef)
    }
  }
}