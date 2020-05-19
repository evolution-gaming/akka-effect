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
  * @tparam E event
  * @tparam C command
  */
trait EventSourced[F[_], S, E, C] {

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
  def start: Resource[F, RecoveryStarted[F, S, E, C]]
}


object EventSourced {

  implicit class EventSourcedOps[F[_], S, E, C](
    val self: EventSourced[F, S, E, C]
  ) extends AnyVal {

    def convert[S1, E1, C1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, E1, C1] = new EventSourced[F, S1, E1, C1] {

      def eventSourcedId = self.eventSourcedId

      def pluginIds = self.pluginIds

      def recovery = self.recovery

      def start = {
        self.start.map { _.convert(sf, s1f, ef, e1f, cf) }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: S1 => F[S],
      ef: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, E1, C1] = new EventSourced[F, S1, E1, C1] {

      def eventSourcedId = self.eventSourcedId

      def pluginIds = self.pluginIds

      def recovery = self.recovery

      def start = {
        self.start.map { _.widen(sf, ef, cf) }
      }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourced[F, Any, Any, Any] = {
      widen[Any, Any, Any](sf, ef, cf)
    }
  }
}