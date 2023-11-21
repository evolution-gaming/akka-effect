package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Resource}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.ToFuture

import scala.reflect.ClassTag

object EventSourcedActorOf {

  /**
    * Describes lifecycle of entity with regards to event sourcing & PersistentActor
    * Lifecycle phases:
    *
    * 1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery
    * 2. Recovering     : reading snapshot and replaying events
    * 3. Receiving      : receiving commands and potentially storing events & snapshots
    * 4. Termination    : triggers all release hooks of allocated resources within previous phases
    *
    * @tparam S snapshot
    * @tparam E event
    * @tparam C command
    */
  type Type[F[_], S, E, C] = EventSourcedOf[
    F,
    Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], ActorOf.Stop]]]
  ]

  def actor[F[_]: Async: ToFuture, S, E, C: ClassTag](
    eventSourcedOf: Type[F, S, E, C],
    eventSourcedStore: EventSourcedStore[F, S, E],
  ): Actor = ActorOf[F] { actorCtx =>
    for {
      eventSourced <- eventSourcedOf(actorCtx).toResource
      recoveryStarted <- eventSourced.value
      recovery <- eventSourcedStore.recover(eventSourced.eventSourcedId)

      recovering <- recoveryStarted(
        recovery.snapshot.map(_.metadata.seqNr).getOrElse(SeqNr.Min),
        recovery.snapshot.map(_.asOffer)
      )

      replaying = for {
        replay <- recovering.replay
        events = recovery.events
        seqNrL <- events
          .foldWhileM(SeqNr.Min) {
            case (_, event) =>
              replay(event.event, event.seqNr).as(event.seqNr.asLeft[Unit])
          }
          .toResource
        seqNr <- seqNrL
          .as(new IllegalStateException("should newer happened"))
          .swap
          .liftTo[F]
          .toResource
      } yield seqNr

      seqNr <- replaying.use(_.pure[F]).toResource
      journaller <- eventSourcedStore
        .journaller(eventSourced.eventSourcedId, seqNr)
      snapshotter <- eventSourcedStore.snapshotter(eventSourced.eventSourcedId)
      receive <- recovering.completed(seqNr, journaller, snapshotter)
    } yield receive.contramapM[Envelope[Any]](_.cast[F, C])
  }

  private implicit class SnapshotOps[S](val snapshot: Snapshot[S])
      extends AnyVal {

    def asOffer: SnapshotOffer[S] =
      SnapshotOffer(
        SnapshotMetadata(snapshot.metadata.seqNr, snapshot.metadata.timestamp),
        snapshot.snapshot
      )

  }

}
