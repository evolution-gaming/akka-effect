package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream

/** Persistent event store API used in event-sourced actors [[EventSourcedActorOf]]. The API consists of two parts: [[EventStore.Read]] and
  * [[EventStore.Write]] that represents reading (actually streaming) and persisting events.
  */
trait EventStore[F[_], A] extends EventStore.Read[F, A] with EventStore.Write[F, A]

object EventStore {

  trait Read[F[_], A] {

    /** Request stream of events starting from [[SeqNr]].
      *
      * @param fromSeqNr
      *   sequence number of first event in stream if any
      * @return
      *   lazy event stream of type [[sstream.Stream]]
      */
    def events(fromSeqNr: SeqNr): F[sstream.Stream[F, Persisted[A]]]
  }

  trait Write[F[_], A] {

    /** Persist events in batches. Outer [[NonEmptyList]] of [[Events#values]] can be used for batching while inner [[NonEmptyList]] must be
      * persisted atomically.
      *
      * @param events
      *   events to be persisted
      * @return
      *   outer F represents request send while inner F represents request complete
      */
    def save(events: Events[Event[A]]): F[F[SeqNr]]

    /** Delete events with sequence number up to [[SeqNr]] inclusive.
      *
      * @param seqNr
      *   sequence number up to which events will be deleted
      * @return
      *   outer F represents request send while inner F represents request complete
      */
    def deleteTo(seqNr: SeqNr): F[F[Unit]]
  }

  /** Persisted data representation
    */
  sealed trait Persisted[+A]

  /** Highest persisted [[SeqNr]] representation. Used as marker of sequence persisted number in case if events not available (were
    * deleted).
    *
    * @param seqNr
    *   (highest) persisted [[SeqNr]]
    */
  final case class HighestSeqNr(seqNr: SeqNr) extends Persisted[Nothing]

  /** Persisted event representation
    *
    * @param event
    *   domain event of type [[A]]
    * @param seqNr
    *   assosiated with event sequence number
    */
  final case class Event[A](event: A, seqNr: SeqNr) extends Persisted[A]

}
