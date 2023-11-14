package com.evolution.akkaeffect.eventsopircing.persistence

/**
  * Event sourcing persistence API: provides snapshot followed by stream of events
  * @tparam F effect
  * @tparam S snapshot
  * @tparam E event
  */
trait EventSourcedStore[F[_], S, E] {

  import EventSourcedStore._

  /**
    * Start recovery by retrieving snapshot (eager, happening on outer F)
    * and preparing for loading events (lazy op, happens on [[Recovery#events()]] stream materialisation)
    * @param id persistent ID
    * @return [[Recovery]] instance, representing __started__ recovery
    */
  def recover(id: Id): F[Recovery[F, S, E]]

}

object EventSourcedStore {

  /** ID of persistent actor
    * @see [[com.evolutiongaming.akkaeffect.persistence.EventSourcedId]]
    * @see [[akka.persistence.PersistentActor.persistenceId]]
    */
  final case class Id(value: String) extends AnyVal

  /**
    * Snapshot lookup criteria
    * @see [[akka.persistence.SnapshotSelectionCriteria]]
    */
  final case class Criteria(maxSequenceNr: Long = Long.MaxValue,
                            maxTimestamp: Long = Long.MaxValue,
                            minSequenceNr: Long = 0L,
                            minTimestamp: Long = 0L)

}
