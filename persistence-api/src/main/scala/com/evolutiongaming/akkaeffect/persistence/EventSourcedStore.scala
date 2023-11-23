package com.evolutiongaming.akkaeffect.persistence

import cats.Applicative
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
    * Start recovery by retrieving snapshot (eager, happening on resource allocation)
    * and preparing for loading events (lazy op, happens on [[Recovery#events()]] stream materialisation)
    * @param id persistent ID
    * @return [[Recovery]] represents started recovery, resource will be released upon actor termination
    */
  def recover(id: EventSourcedId): Resource[F, Recovery[F, S, E]]

  /**
    * Create [[Journaller]] capable of persisting and deleting events
    * @param id persistent ID
    * @param seqNr recovered [[SeqNr]] or [[SeqNr.Min]]
    * @return resource will be released upon actor termination
    */
  def journaller(id: EventSourcedId,
                 seqNr: SeqNr): Resource[F, Journaller[F, E]]

  /**
    * Create [[Snapshotter]] capable of persisting and deleting snapshots
    * @param id persistent ID
    * @return resource will be released upon actor termination
    */
  def snapshotter(id: EventSourcedId): Resource[F, Snapshotter[F, S]]
}

object EventSourcedStore {

  def const[F[_]: Applicative, S, E](
    recovery_ : Recovery[F, S, E],
    journaller_ : Journaller[F, E],
    snapshotter_ : Snapshotter[F, S]
  ): EventSourcedStore[F, S, E] = new EventSourcedStore[F, S, E] {

    import cats.syntax.all._

    override def recover(id: EventSourcedId): Resource[F, Recovery[F, S, E]] =
      recovery_.pure[Resource[F, *]]

    override def journaller(id: EventSourcedId,
                            seqNr: SeqNr): Resource[F, Journaller[F, E]] =
      journaller_.pure[Resource[F, *]]

    override def snapshotter(
      id: EventSourcedId
    ): Resource[F, Snapshotter[F, S]] =
      snapshotter_.pure[Resource[F, *]]
  }

}
