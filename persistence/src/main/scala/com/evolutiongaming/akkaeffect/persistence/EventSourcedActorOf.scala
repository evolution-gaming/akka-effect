package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence.SnapshotSelectionCriteria
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Concurrent, Ref, Resource}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.*
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.catshelper.{Log, LogOf, ToFuture}

import java.time.Instant

object EventSourcedActorOf {

  // format: off
  /** Describes lifecycle of entity with regards to event sourcing & PersistentActor Lifecycle phases:
    * 1. RecoveryStarted: we have id in place and can decide whether we should continue with recovery
    * 2. Recovering: reading snapshot and replaying events
    * 3. Receiving: receiving commands and potentially storing events & snapshots
    * 4. Termination: triggers all release hooks of allocated resources within previous phases
    *
    * Types, describing each phase, are (simplified) a functions from data (available on the current phase) to next phase: 
    * RecoveryStarted -> Recovering -> Receiving. 
    * Termination phase described via aggregation of [[Resource]] release callbacks. Please refer to phase types for more details.
    *
    * @tparam S snapshot
    * @tparam E event
    * @tparam C command
    */
  type Lifecycle[F[_], S, E, C] = Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], ActorOf.Stop]]]
  // format: on

  // format: off
  /** Factory method aimed to create [[Actor]] capable of handling commands, saving snapshots and producing events. The actor uses Event
    * Sourcing pattern to persist events/snapshots and recover state from them later.
    *
    * Actor's lifecycle is described by type [[Lifecycle]] and consists of multiple phases, such as recovering, receiving messages and
    * terminating. Recovery happens on actor's startup and is about constructing latest actor state from snapshot and followed events. On
    * receiving phase actor handles incoming commands and changes its state. Each state change is represented by events, that are persisted and
    * later used in recovery phase. Termination happens on actor's shutdown (technically it happens as part of [[Actor.postStop]], check
    * [[ActorOf]] for more details).
    *
    * Persistence layer, used to store/recover events and snapshots, provided by [[EventSourcedPersistence]].
    * 
    * @tparam F effect type
    * @tparam S snapshot type
    * @tparam E event type
    *
    * @param eventSourcedOf
    *   actor logic used to recover and handle messages
    * @param persistence
    *   persistence used for event sourcing
    * @return
    *   instance of [[Actor]]
    */
  // format: on
  def actor[F[_]: Async: ToFuture: LogOf, S, E](
    eventSourcedOf: EventSourcedOf[F, Lifecycle[F, S, E, Any]],
    persistence: EventSourcedPersistence[F, S, E],
  ): Actor = ActorOf[F] {
    receiveOf(eventSourcedOf, persistence)
  }

  private[evolutiongaming] def receiveOf[F[_]: Async: LogOf, S, E](
    eventSourcedOf: EventSourcedOf[F, Lifecycle[F, S, E, Any]],
    persistence: EventSourcedPersistence[F, S, E],
  ): ReceiveOf[F, Envelope[Any], ActorOf.Stop] = { actorCtx =>
    LogOf
      .log[F, EventSourcedActorOf.type]
      .toResource
      .flatMap { implicit log0 =>
        implicit val log =
          log0
            .prefixed(actorCtx.self.path.name)
            .mapK(Resource.liftK[F])
        val receive = for {
          eventSourced    <- eventSourcedOf(actorCtx).toResource
          recoveryStarted <- eventSourced.value

          snapshotStore <- persistence.snapshotStore(eventSourced).toResource
          eventStore    <- persistence.eventStore(eventSourced).toResource

          snapshot <- snapshotStore.latest.toResource
          _        <- log.debug(s"using snapshot $snapshot")

          recovering <- recoveryStarted(
            snapshot.map(_.metadata.seqNr).getOrElse(SeqNr.Min),
            snapshot.map(_.asOffer),
          )

          replay = recovering.replay

          seqNr <- replay.use { replay =>
            // used to recover snapshot, i.e. the snapshot stored with [[snapSeqNr]] will be loaded, if any
            val snapSeqNr = snapshot.map(_.metadata.seqNr).getOrElse(SeqNr.Min)
            // used to recover events _following_ the snapshot OR if no snapshot available then [[SeqNr.Min]]
            val fromSeqNr = snapshot.map(_.metadata.seqNr + 1).getOrElse(SeqNr.Min)
            for {
              _      <- log.debug(s"snapshot seqNr: $snapSeqNr, load events from seqNr: $fromSeqNr").allocated
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

          _          <- log.debug(s"recovery completed with seqNr $seqNr")
          journaller <- eventStore.asJournaller(actorCtx, seqNr).toResource
          receive    <- recovering.completed(seqNr, journaller, snapshotStore.asSnapshotter)
        } yield receive

        receive.onError {
          case err: Throwable => log.error(s"failed to allocate receive", err)
        }
      }
  }

  implicit final private class SnapshotOps[S](val snapshot: SnapshotStore.Offer[S]) extends AnyVal {

    def asOffer: SnapshotOffer[S] =
      SnapshotOffer(
        SnapshotMetadata(snapshot.metadata.seqNr, snapshot.metadata.timestamp),
        snapshot.snapshot,
      )

  }

  implicit final private class SnapshotStoreOps[F[_], A](val store: SnapshotStore[F, A]) extends AnyVal {

    def asSnapshotter: Snapshotter[F, A] = new Snapshotter[F, A] {

      def save(seqNr: SeqNr, snapshot: A): F[F[Instant]] = store.save(seqNr, snapshot)

      def delete(seqNr: SeqNr): F[F[Unit]] = store.delete(seqNr)

      def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]] = store.delete {
        SnapshotStore.Criteria(
          maxSequenceNr = criteria.maxSequenceNr,
          maxTimestamp = criteria.maxTimestamp,
          minSequenceNr = criteria.minSequenceNr,
          minTimestamp = criteria.minTimestamp,
        )
      }
    }
  }

  implicit final private class EventStoreOps[F[_], E](val store: EventStore[F, E]) extends AnyVal {

    def asJournaller(actorCtx: ActorCtx[F], seqNr: SeqNr)(implicit F: Concurrent[F], log: Log[F]): F[Journaller[F, E]] =
      for {
        seqNrRef <- Ref[F].of(seqNr)
      } yield new Journaller[F, E] {
        val append = new Append[F, E] {

          def apply(events0: Events[E]): F[F[SeqNr]] =
            seqNrRef
              .modify { seqNr =>
                events0.mapAccumulate(seqNr) {
                  case (seqNr0, event) =>
                    val seqNr1 = seqNr0 + 1
                    seqNr1 -> EventStore.Event(event, seqNr1)
                }
              }
              .flatMap { events =>
                def handleError(err: Throwable) = {
                  val from = events.values.head.head.seqNr
                  val to   = events.values.last.last.seqNr
                  stopActor(from, to, err)
                }
                store
                  .save(events)
                  .onError(handleError)
                  .flatTap(_.onError(handleError))
              }

          private def stopActor(from: SeqNr, to: SeqNr, error: Throwable): F[Unit] =
            for {
              _ <- log.error(s"failed to append events with seqNr range [$from .. $to], stopping actor", error)
              _ <- actorCtx.stop
            } yield {}

        }

        val deleteTo = new DeleteEventsTo[F] {

          def apply(seqNr: SeqNr): F[F[Unit]] = store.deleteTo(seqNr)

        }
      }
  }
}
