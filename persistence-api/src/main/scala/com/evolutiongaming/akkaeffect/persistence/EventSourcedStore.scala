package com.evolutiongaming.akkaeffect.persistence

import cats.effect.kernel.Resource
import com.evolutiongaming.sstream.Stream

/**
  * Event sourcing persistence API: provides snapshot followed by stream of events
  *
  * @tparam F effect
  * @tparam S snapshot
  * @tparam E event
  */
trait EventSourcedStore[F[_], S, E] {

  import EventSourcedStore.Recovery

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

  /**
    * Representation of __started__ recovery process:
    * snapshot is already loaded in memory (if any)
    * while events will be loaded only on materialisation of [[Stream]]
    *
    * @tparam F effect
    * @tparam S snapshot
    * @tparam E event
    */
  trait Recovery[F[_], S, E] {

    def snapshot: Option[Snapshot[S]]
    def events: Stream[F, Event[E]]

  }

  object Recovery {

    private case class Const[F[_], S, E](snapshot: Option[Snapshot[S]],
                                         events: Stream[F, Event[E]])
        extends Recovery[F, S, E]

    def const[F[_], S, E](snapshot: Option[Snapshot[S]],
                          events: Stream[F, Event[E]]): Recovery[F, S, E] =
      Const(snapshot, events)

  }

  def const[F[_], S, E](
    recovery: Recovery[F, S, E],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): EventSourcedStore[F, S, E] = {

    val (r, j, s) = (recovery, journaller, snapshotter)

    new EventSourcedStore[F, S, E] {

      import cats.syntax.all._

      override def recover(id: EventSourcedId): Resource[F, Recovery[F, S, E]] =
        r.pure[Resource[F, *]]

      override def journaller(id: EventSourcedId,
                              seqNr: SeqNr): Resource[F, Journaller[F, E]] =
        j.pure[Resource[F, *]]

      override def snapshotter(
        id: EventSourcedId
      ): Resource[F, Snapshotter[F, S]] =
        s.pure[Resource[F, *]]
    }
  }

}
