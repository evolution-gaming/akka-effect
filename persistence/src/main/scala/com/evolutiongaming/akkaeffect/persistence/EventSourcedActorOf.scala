package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Resource}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.ToFuture

import scala.reflect.ClassTag

object EventSourcedActorOf {

  /** Describes lifecycle of entity with regards to event sourcing & PersistentActor Lifecycle phases:
    *
    *   1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery 2. Recovering : reading snapshot and
    *      replaying events 3. Receiving : receiving commands and potentially storing events & snapshots 4. Termination : triggers all
    *      release hooks of allocated resources within previous phases
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

  def actor[F[_]: Async: ToFuture, S, E, C: ClassTag](
    eventSourcedOf: EventSourcedOf[F, Lifecycle[F, S, E, C]],
    persistenceOf: EventSourcedPersistenceOf[F, S, E]
  ): Actor = ActorOf[F] { actorCtx =>
    for {
      eventSourced    <- eventSourcedOf(actorCtx).toResource
      persistentId     = eventSourced.eventSourcedId
      recoveryStarted <- eventSourced.value

      persistence <- persistenceOf(eventSourced).toResource
      recovery    <- persistence.recover.toResource

      recovering <- recoveryStarted(
        recovery.snapshot.map(_.metadata.seqNr).getOrElse(SeqNr.Min),
        recovery.snapshot.map(_.asOffer)
      )

      seqNr <- recovering.replay.use { replay =>
        for {
          seqNrL <- recovery.events
            .foldWhileM(SeqNr.Min) {
              case (_, event) =>
                replay(event.event, event.seqNr).as(event.seqNr.asLeft[Unit])
            }
          seqNr <- seqNrL
            .as(new IllegalStateException("should newer happened"))
            .swap
            .liftTo[F]
        } yield seqNr
      }.toResource

      journaller  <- persistence.journaller(seqNr).toResource
      snapshotter <- persistence.snapshotter.toResource
      receive     <- recovering.completed(seqNr, journaller, snapshotter)
    } yield receive.contramapM[Envelope[Any]](_.cast[F, C])
  }

  implicit private class SnapshotOps[S](val snapshot: Snapshot[S]) extends AnyVal {

    def asOffer: SnapshotOffer[S] =
      SnapshotOffer(
        SnapshotMetadata(snapshot.metadata.seqNr, snapshot.metadata.timestamp),
        snapshot.snapshot
      )

  }

}
