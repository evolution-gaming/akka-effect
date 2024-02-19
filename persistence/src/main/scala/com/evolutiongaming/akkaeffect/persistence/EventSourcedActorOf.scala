package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence.SnapshotSelectionCriteria
import cats.effect.Concurrent
import cats.effect.Resource
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper.OpsCatsHelper
import com.evolutiongaming.catshelper.ToFuture

import java.time.Instant
import scala.reflect.ClassTag

object EventSourcedActorOf {

  /** Describes lifecycle of entity with regards to event sourcing & PersistentActor Lifecycle phases:
    *
    *   1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery 2. Recovering : reading snapshot and
    *      replaying events 3. Receiving : receiving commands and potentially storing events & snapshots 4. Termination : triggers all
    *      release hooks of allocated resources within previous phases
    *
    * Types, describing each phase, are (simplified) a functions from data (available on the current phase) to next phase: RecoveryStarted
    * -> Recovering -> Receiving. Termination phase described via aggregation of [[Resource]] release callbacks. Please refer to phase types
    * for more details.
    *
    * @tparam S
    *   snapshot
    * @tparam E
    *   event
    * @tparam C
    *   command
    */
  type Lifecycle[F[_], S, E, C] =
    Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], ActorOf.Stop]]]

  /** Factory method aimed to create [[Actor]] capable of handling commands of type [[C]], saving snapshots of type [[S]] and producing
    * events of type [[E]]. The actor uses Event Sourcing pattern to persist events (and snapshots) and recover state from events (and
    * snapshot) later.
    *
    * Actor' lifecycle described by type [[Lifecycle]] and consists of multiple phases, such as recovering, receiving messages and
    * terminating. Recovery happeneds on actor' startup and is about constucting latest actor' state from snapshot and followed events. On
    * receiving phase actor handles incoming commands and chages its state. Each state' change represented by events, that are persisted and
    * later used in recovery phase. Terminating happeneds on actor shutdown (technically it happens as part of [[Actor.postStop]], check
    * [[ActorOf]] for more details).
    *
    * Persistence layer, used to store/recover events and snapshots, provided by [[EventSourcedPersistence]].
    *
    * @param eventSourcedOf
    *   actor logic used to recover and handle messages
    * @param persistence
    *   persistence used for event sourcing
    * @return
    *   instance of [[Actor]]
    */
  def actor[F[_]: Concurrent: ToFuture, S, E, C: ClassTag](
    eventSourcedOf: EventSourcedOf[F, Lifecycle[F, S, E, C]],
    persistence: EventSourcedPersistence[F]
  ): Actor = ActorOf[F] {
    receiveOf(eventSourcedOf, persistence)
  }

  private[evolutiongaming] def receiveOf[F[_]: Concurrent, S, E, C: ClassTag](
    eventSourcedOf: EventSourcedOf[F, Lifecycle[F, S, E, C]],
    persistence: EventSourcedPersistence[F]
  ): ReceiveOf[F, Envelope[Any], ActorOf.Stop] = { actorCtx =>
    for {
      eventSourced    <- eventSourcedOf(actorCtx).toResource
      recoveryStarted <- eventSourced.value

      snapshotStore <- persistence.snapshotStore[S](eventSourced).toResource
      eventStore    <- persistence.eventStore[E](eventSourced).toResource

      snapshot <- snapshotStore.latest.toResource

      recovering <- recoveryStarted(
        snapshot.map(_.metadata.seqNr).getOrElse(SeqNr.Min),
        snapshot.map(_.asOffer)
      )

      seqNr <- recovering.replay.use { replay =>
        val snapSeqNr = snapshot.map(_.metadata.seqNr).getOrElse(SeqNr.Min)
        val fromSeqNr = snapshot.map(_.metadata.seqNr + 1).getOrElse(SeqNr.Min)
        for {
          events <- eventStore.events(fromSeqNr)
          seqNrL <- events.foldWhileM(snapSeqNr) {
            case (_, EventStore.Event(event, seqNr)) => replay(event, seqNr).as(seqNr.asLeft[Unit])
            case (_, EventStore.HighestSeqNr(seqNr)) => seqNr.asLeft[Unit].pure[F]
          }
          seqNr <- seqNrL match {
            case Left(seqNr) => seqNr.pure[F]
            case Right(_)    =>
              // function used in events.foldWhileM always returns Left
              // and sstream.Stream.foldWhileM returns Right only if passed function do so
              // thus makes getting Right here impossible
              new IllegalStateException("should never happened").raiseError[F, SeqNr]
          }
        } yield seqNr
      }.toResource

      currentSeqNr <- Ref[F].of(seqNr).toResource

      snapshotter = new Snapshotter[F, S] {

        def save(seqNr: SeqNr, snapshot: S): F[F[Instant]] = snapshotStore.save(seqNr, snapshot)

        def delete(seqNr: SeqNr): F[F[Unit]] = snapshotStore.delete(seqNr)

        def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]] = snapshotStore.delete(criteria.asStoreCriteria)

      }

      journaller = new Journaller[F, E] {

        val append = new Append[F, E] {

          def apply(events: Events[E]): F[F[SeqNr]] =
            currentSeqNr
              .modify { seqNr0 =>
                events.mapAccumulate(seqNr0) {
                  case (seqNr0, event) =>
                    val seqNr1 = seqNr0 + 1
                    seqNr1 -> EventStore.Event(event, seqNr1)
                }
              }
              .flatMap { events =>
                eventStore.save(events)
              }

        }

        val deleteTo = new DeleteEventsTo[F] {

          def apply(seqNr: SeqNr): F[F[Unit]] = eventStore.deleteTo(seqNr)

        }

      }

      receive <- recovering.completed(seqNr, journaller, snapshotter)
    } yield receive.contramapM[Envelope[Any]](_.cast[F, C])
  }

  implicit private class SnapshotOps[S](val snapshot: SnapshotStore.Offer[S]) extends AnyVal {

    def asOffer: SnapshotOffer[S] =
      SnapshotOffer(
        SnapshotMetadata(snapshot.metadata.seqNr, snapshot.metadata.timestamp),
        snapshot.snapshot
      )

  }

  implicit private class CriteriaOps(val criteria: SnapshotSelectionCriteria) extends AnyVal {

    def asStoreCriteria: SnapshotStore.Criteria =
      SnapshotStore.Criteria(
        maxSequenceNr = criteria.maxSequenceNr,
        maxTimestamp = criteria.maxTimestamp,
        minSequenceNr = criteria.minSequenceNr,
        minTimestamp = criteria.minTimestamp
      )
  }

}
