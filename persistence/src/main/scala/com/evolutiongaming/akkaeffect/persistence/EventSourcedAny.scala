package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
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
  * @tparam R reply
  */
trait EventSourcedAny[F[_], S, C, E, R] {

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
    * @return None to stop actor, Some to continue
    */
  def start: Resource[F, Option[RecoveryStartedAny[F, S, C, E, R]]]
}


object EventSourcedAny {

}