package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence.SnapshotSelectionCriteria

import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Resource, Ref}
import cats.syntax.all._

import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.ToFuture

import scala.reflect.ClassTag
import java.time.Instant

object EventSourcedActorOf {

  /** Describes lifecycle of entity with regards to event sourcing & PersistentActor Lifecycle phases:
    *
    *   1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery 
    *   2. Recovering : reading snapshot and replaying events 
    *   3. Receiving : receiving commands and potentially storing events & snapshots 
    *   4. Termination : triggers all release hooks of allocated resources within previous phases
    *
    * @tparam S snapshot
    * @tparam E event
    * @tparam C command
    */
  type Lifecycle[F[_], S, E, C] =
    Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], ActorOf.Stop]]]

  def actor[F[_]: Async: ToFuture, S, E, C: ClassTag](
    eventSourcedOf: EventSourcedOf[F, Lifecycle[F, S, E, C]],
    persistence: EventSourcedPersistence[F]
  ): Actor = ActorOf[F] { actorCtx =>
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
          seqNr <- seqNrL
            .as(new IllegalStateException("should newer happened"))
            .swap
            .liftTo[F]
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
                val seqNr1 = seqNr0 + events.size
                seqNr1 -> seqNr0
              }
              .map { seqNr0 =>
                var seqNr = seqNr0
                def nextSeqNr: SeqNr = {
                  seqNr = seqNr + 1
                  seqNr
                }
                events.map(e => EventStore.Event(e, nextSeqNr))
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
