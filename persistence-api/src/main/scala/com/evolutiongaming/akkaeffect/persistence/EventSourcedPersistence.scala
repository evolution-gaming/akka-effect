package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream.Stream
import cats.Applicative
import cats.syntax.all._

/** Event sourcing persistence API: provides snapshot followed by stream of events. After recovery completed, provides instances of
  * [[Journaller]] and [[Snapshotter]].
  *
  * @tparam F
  *   Effect type.
  * @tparam S
  *   Snapshot type.
  * @tparam E
  *   Event type.
  */
trait EventSourcedPersistence[F[_], S, E] {

  import EventSourcedPersistence.Recovery

  /** Start recovery by retrieving snapshot (eager, happening on [[F]]) and preparing for loading events (lazy op, happens on
    * [[Recovery#events()]] stream materialisation).
    * @return
    *   Instance of [[Recovery]] that represents started recovery.
    */
  def recover: F[Recovery[F, S, E]]

  /** Create [[Journaller]] capable of persisting and deleting events.
    * @param seqNr
    *   Recovered [[SeqNr]] or [[SeqNr.Min]] if nothing was recovered.
    */
  def journaller(seqNr: SeqNr): F[Journaller[F, E]]

  /** Create [[Snapshotter]] capable of persisting and deleting snapshots.
    */
  def snapshotter: F[Snapshotter[F, S]]
}

object EventSourcedPersistence {

  /** Representation of __started__ recovery process: snapshot is already loaded in memory (if any) while events will be loaded only on
    * materialisation of [[Stream]]
    *
    * @tparam F
    *   effect
    * @tparam S
    *   snapshot
    * @tparam E
    *   event
    */
  trait Recovery[F[_], S, E] {

    def snapshot: Option[Snapshot[S]]
    def events: Stream[F, Event[E]]

  }

  object Recovery {

    private case class Const[F[_], S, E](snapshot: Option[Snapshot[S]], events: Stream[F, Event[E]]) extends Recovery[F, S, E]

    def const[F[_], S, E](snapshot: Option[Snapshot[S]], events: Stream[F, Event[E]]): Recovery[F, S, E] =
      Const(snapshot, events)

  }

  def const[F[_]: Applicative, S, E](
    recovery: Recovery[F, S, E],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): EventSourcedPersistence[F, S, E] = {

    val (r, j, s) = (recovery, journaller, snapshotter)

    new EventSourcedPersistence[F, S, E] {

      override def recover: F[Recovery[F, S, E]] = r.pure[F]

      override def journaller(seqNr: SeqNr): F[Journaller[F, E]] = j.pure[F]

      override def snapshotter: F[Snapshotter[F, S]] = s.pure[F]
    }
  }

}
