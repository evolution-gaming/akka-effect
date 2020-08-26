package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.effect.Resource
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.{Envelope, Receive}

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
  * @tparam A recovery result
  */
trait EventSourced[F[_], S, E, A] {

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
  def start: Resource[F, RecoveryStarted[F, S, E, A]]
}


object EventSourced {

  implicit class EventSourcedOps[F[_], S, E, A](
    val self: EventSourced[F, S, E, A]
  ) extends AnyVal {

    def convert[S1, E1, A1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      af: A => Resource[F, A1])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, E1, A1] = new EventSourced[F, S1, E1, A1] {

      def eventSourcedId = self.eventSourcedId

      def pluginIds = self.pluginIds

      def recovery = self.recovery

      def start = {
        self.start.map { _.convert(sf, s1f, ef, e1f, af) }
      }
    }


    def map[A1](
      f: A => A1)(implicit
      F: Applicative[F]
    ): EventSourced[F, S, E, A1] = new EventSourced[F, S, E, A1] {
      def eventSourcedId = self.eventSourcedId

      def pluginIds = self.pluginIds

      def recovery = self.recovery

      def start = {
        self.start.map { _.map(f) }
      }
    }


    def mapM[A1](f: A => Resource[F, A1])(implicit F: Applicative[F]): EventSourced[F, S, E, A1] = {
      new EventSourced[F, S, E, A1] {
        def eventSourcedId = self.eventSourcedId

        def pluginIds = self.pluginIds

        def recovery = self.recovery

        def start = {
          self.start.map { _.mapM(f) }
        }
      }
    }
  }


  implicit class EventSourcedReceiveEnvelopeOps[F[_], S, E, C](
    val self: EventSourced[F, S, E, Receive[F, Envelope[C], Boolean]]
  ) extends AnyVal {

    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: S1 => F[S],
      ef: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, E1, Receive[F, Envelope[C1], Boolean]] = {
      new EventSourced[F, S1, E1, Receive[F, Envelope[C1], Boolean]] {

        def eventSourcedId = self.eventSourcedId

        def pluginIds = self.pluginIds

        def recovery = self.recovery

        def start = {
          self.start.map { _.widen(sf, ef, cf) }
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourced[F, Any, Any, Receive[F, Envelope[Any], Boolean]] = {
      widen[Any, Any, Any](sf, ef, cf)
    }
  }
}