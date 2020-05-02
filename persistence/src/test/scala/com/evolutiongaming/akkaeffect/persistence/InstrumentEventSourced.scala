package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

import akka.actor.ActorRef
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect._

object InstrumentEventSourced {

  def apply[F[_] : Sync, S, C, E](
    actions: Ref[F, List[Action[S, C, E]]],
    eventSourcedOf: EventSourcedOf[F, S, C, E]
  ): EventSourcedOf[F, S, C, E] = {

    def record(action: Action[S, C, E]) = actions.update { action :: _ }

    def resource[A](allocate: Action[S, C, E], release: Action[S, C, E]) = {
      Resource.make {
        record(allocate)
      } { _ =>
        record(release)
      }
    }

    actorCtx: ActorCtx[F] => {
      for {
        eventSourced <- eventSourcedOf(actorCtx)
        _            <- record(Action.Created(
          eventSourced.eventSourcedId,
          eventSourced.recovery,
          eventSourced.pluginIds))
      } yield {
        new EventSourced[F, S, C, E] {

          def eventSourcedId = eventSourced.eventSourcedId

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            for {
              recoveryStarted <- eventSourced.start
              _               <- resource(Action.Started, Action.Released)
            } yield {
              RecoveryStarted[F, S, C, E] { (seqNr, snapshotOffer) =>

                val snapshotOffer1 = snapshotOffer.map { snapshotOffer =>
                  val metadata = snapshotOffer.metadata.copy(timestamp = Instant.ofEpochMilli(0))
                  snapshotOffer.copy(metadata = metadata)
                }

                for {
                  recovering <- recoveryStarted(seqNr, snapshotOffer)
                  _          <- resource(Action.RecoveryAllocated(snapshotOffer1), Action.RecoveryReleased)
                } yield {

                  new Recovering[F, S, C, E] {

                    def replay = {
                      for {
                        _      <- resource(Action.ReplayAllocated, Action.ReplayReleased)
                        replay <- recovering.replay
                      } yield {
                        Replay[F, E] { (seqNr, event) =>
                          for {
                            _ <- replay(seqNr, event)
                            _ <- record(Action.Replayed(event, seqNr))
                          } yield {}
                        }
                      }
                    }

                    def completed(
                      seqNr: SeqNr,
                      journaller: Journaller[F, E],
                      snapshotter: Snapshotter[F, S]
                    ) = {

                      val journaller1 = new Journaller[F, E] {

                        def append = (events: Nel[Nel[E]]) => {
                          for {
                            _     <- record(Action.AppendEvents(events))
                            seqNr <- journaller.append(events)
                            _     <- record(Action.AppendEventsOuter)
                          } yield {
                            for {
                              seqNr <- seqNr
                              _     <- record(Action.AppendEventsInner(seqNr))
                            } yield seqNr
                          }
                        }

                        def deleteTo = (seqNr: SeqNr) => {
                          for {
                            _ <- record(Action.DeleteEventsTo(seqNr))
                            a <- journaller.deleteTo(seqNr)
                            _ <- record(Action.DeleteEventsToOuter)
                          } yield {
                            for {
                              a <- a
                              _ <- record(Action.DeleteEventsToInner)
                            } yield a
                          }
                        }
                      }

                      val snapshotter1 = new Snapshotter[F, S] {

                        def save(seqNr: SeqNr, snapshot: S) = {
                          for {
                            _ <- record(Action.SaveSnapshot(seqNr, snapshot))
                            a <- snapshotter.save(seqNr, snapshot)
                            _ <- record(Action.SaveSnapshotOuter)
                          } yield for {
                            a <- a
                            _ <- record(Action.SaveSnapshotInner)
                          } yield a
                        }

                        def delete(seqNr: SeqNr) = {
                          for {
                            _ <- record(Action.DeleteSnapshot(seqNr))
                            a <- snapshotter.delete(seqNr)
                            _ <- record(Action.DeleteSnapshotOuter)
                          } yield {
                            for {
                              a <- a
                              _ <- record(Action.DeleteSnapshotInner)
                            } yield a
                          }
                        }

                        def delete(criteria: SnapshotSelectionCriteria) = {
                          for {
                            _ <- record(Action.DeleteSnapshots(criteria))
                            a <- snapshotter.delete(criteria)
                            _ <- record(Action.DeleteSnapshotsOuter)
                          } yield {
                            for {
                              a <- a
                              _ <- record(Action.DeleteSnapshotsInner)
                            } yield a
                          }
                        }
                      }

                      for {
                        receive <- recovering.completed(seqNr, journaller1, snapshotter1)
                        _       <- resource(Action.ReceiveAllocated(seqNr), Action.ReceiveReleased)
                      } yield {
                        Receive[F, C] { (msg, sender) =>
                          for {
                            stop <- receive(msg, sender)
                            _    <- record(Action.Received(msg, sender, stop))
                          } yield stop
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }


  sealed trait Action[+S, +C, +E]

  object Action {

    final case class Created(
      eventSourcedId: EventSourcedId,
      recovery: Recovery,
      pluginIds: PluginIds
    ) extends Action[Nothing, Nothing, Nothing]


    final case object Started extends Action[Nothing, Nothing, Nothing]

    final case object Released extends Action[Nothing, Nothing, Nothing]


    final case class RecoveryAllocated[S](
      snapshotOffer: Option[SnapshotOffer[S]],
    ) extends Action[S, Nothing, Nothing]

    final case object RecoveryReleased extends Action[Nothing, Nothing, Nothing]


    final case object ReplayAllocated extends Action[Nothing, Nothing, Nothing]

    final case object ReplayReleased extends Action[Nothing, Nothing, Nothing]


    final case class Replayed[S, E](event: E, seqNr: SeqNr) extends Action[S, Nothing, E]


    final case class AppendEvents[E](events: Nel[Nel[E]]) extends Action[Nothing, Nothing, E]

    final case object AppendEventsOuter extends Action[Nothing, Nothing, Nothing]

    final case class AppendEventsInner(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing]


    final case class DeleteEventsTo(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing]

    final case object DeleteEventsToOuter extends Action[Nothing, Nothing, Nothing]

    final case object DeleteEventsToInner extends Action[Nothing, Nothing, Nothing]


    final case class SaveSnapshot[S](seqNr: SeqNr, snapshot: S) extends Action[S, Nothing, Nothing]

    final case object SaveSnapshotOuter extends Action[Nothing, Nothing, Nothing]

    final case object SaveSnapshotInner extends Action[Nothing, Nothing, Nothing]


    final case class DeleteSnapshot(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing]

    final case object DeleteSnapshotOuter extends Action[Nothing, Nothing, Nothing]

    final case object DeleteSnapshotInner extends Action[Nothing, Nothing, Nothing]


    final case class DeleteSnapshots(criteria: SnapshotSelectionCriteria) extends Action[Nothing, Nothing, Nothing]

    final case object DeleteSnapshotsOuter extends Action[Nothing, Nothing, Nothing]

    final case object DeleteSnapshotsInner extends Action[Nothing, Nothing, Nothing]

    final case class ReceiveAllocated[S](seqNr: SeqNr) extends Action[S, Nothing, Nothing]

    final case object ReceiveReleased extends Action[Nothing, Nothing, Nothing]

    final case class Received[C](
      cmd: C,
      sender: ActorRef,
      stop: Receive1.Stop
    ) extends Action[Nothing, C, Nothing]
  }
}
