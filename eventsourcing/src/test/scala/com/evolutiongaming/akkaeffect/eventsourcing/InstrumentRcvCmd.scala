package com.evolutiongaming.akkaeffect.eventsourcing

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.data.{NonEmptyList => Nel}
import com.evolutiongaming.akkaeffect.persistence.SeqNr

object InstrumentRcvCmd {

  def apply[F[_] : Sync, S, C, E](receiveCmd: ReceiveCmd[F, S, C, E]): F[(ReceiveCmd[F, S, C, E], F[List[Action[S, C, E]]])] = {

    type Action = InstrumentRcvCmd.Action[S, C, E]

    trait Actions {

      def add(a: Action): F[Unit]

      def get: F[List[Action]]
    }

    object Actions {

      def apply(): F[Actions] = {
        Ref[F]
          .of(List.empty[Action])
          .map { ref =>
            new Actions {

              def add(a: Action) = ref.update { a :: _ }

              def get = ref.get.map { _.reverse }
            }
          }
      }
    }

    for {
      actions <- Actions()
    } yield {
      val result = new ReceiveCmd[F, S, C, E] {

        def apply(cmd: C) = {
          for {
            _ <- actions.add(Action.Cmd(cmd))
            validate <- receiveCmd(cmd)
          } yield {
            new Validate[F, S, C, E] {
              def apply(state: S, seqNr: SeqNr) = {
                for {
                  result <- validate(state, seqNr)
                  _      <- actions.add(Action.ProduceEvents(state, result.events, result.state))
                } yield {
                  result
                }
              }
            }
          }
        }
      }

      (result, actions.get)
    }

  }


  sealed trait Action[+S, +C, +E]

  object Action {
    final case class Cmd[C](value: C) extends Action[Nothing, C, Nothing]
    final case class ProduceEvents[S, E](before: S, events: Nel[Nel[E]], after: S) extends Action[S, Nothing, E]
  }

}
