package com.evolutiongaming.akkaeffect.persistence

/**
  * @see [[akka.persistence.PersistentActor.persistenceId]]
  */
final case class EventSourcedId(value: String)