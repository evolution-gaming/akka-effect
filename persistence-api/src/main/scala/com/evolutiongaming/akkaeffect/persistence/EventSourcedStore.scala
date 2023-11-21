package com.evolutiongaming.akkaeffect.persistence

import cats.effect.kernel.Resource

/**
  * Event sourcing persistence API: provides snapshot followed by stream of events
  *
  * @tparam F effect
  * @tparam S snapshot
  * @tparam E event
  */
trait EventSourcedStore[F[_], S, E] {

  /**
    * Start recovery by retrieving snapshot (eager, happening on outer F)
    * and preparing for loading events (lazy op, happens on [[Recovery#events()]] stream materialisation)
    * @param id persistent ID
    * @return [[Recovery]] represents started recovery, resource will be released upon actor termination
    */
  def recover(id: EventSourcedId): Resource[F, Recovery[F, S, E]]

  def journaller(id: EventSourcedId): Resource[F, Journaller[F, E]]

  def snapshotter(id: EventSourcedId): Resource[F, Snapshotter[F, S]]
}
