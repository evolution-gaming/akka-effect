package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.{ReceiveAny, Reply}
import com.evolutiongaming.catshelper.CatsHelper._

object EventSourcedInterop {

  def apply[F[_]: Sync, S, C, E, R](
    eventSourcedOf: EventSourcedOf[F, S, C, E, R]
  ): EventSourcedAnyOf[F, S, C, E] = {
    EventSourcedAnyOf[F, S, C, E, R] { actorCtx =>
      eventSourcedOf(actorCtx).map { eventSourced =>
        new EventSourcedAny[F, S, C, E] {

          def eventSourcedId = eventSourced.eventSourcedId

          def recovery = eventSourced.recovery

          def pluginIds = eventSourced.pluginIds

          def start = {
            eventSourced.start
              .map { recoveryStarted =>
                RecoveryStartedAny[F, S, C, E] { (seqNr, snapshotOffer) =>
                  for {
                    recovering <- recoveryStarted(seqNr, snapshotOffer)
                    initial    <- recovering.initial.toResource
                    stateRef   <- Ref[F].of(initial).toResource
                  } yield {

                    new RecoveringAny[F, S, C, E] {

                      def replay = {
                        recovering
                          .replay
                          .map { replay =>
                            Replay[F, E] { (seqNr, event) =>
                              for {
                                state <- stateRef.get
                                state <- replay(seqNr, state, event)
                                _     <- stateRef.set(state)
                              } yield {}
                            }
                          }
                      }

                      def completed(
                        seqNr: SeqNr,
                        journaller: Journaller[F, E],
                        snapshotter: Snapshotter[F, S]
                      ) = {
                        for {
                          state   <- stateRef.get.toResource
                          receive <- recovering.completed(seqNr, state, journaller, snapshotter)
                        } yield {
                          ReceiveAny[F, C] { (msg, sender) =>
                            val reply = Reply
                              .fromActorRef(to = sender, from = actorCtx.self.some)
                              .narrow[R]
                            receive(msg, reply)
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
}
