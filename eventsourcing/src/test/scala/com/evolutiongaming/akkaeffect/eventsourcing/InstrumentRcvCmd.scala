package com.evolutiongaming.akkaeffect.eventsourcing

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.akkaeffect.eventsourcing

object InstrumentRcvCmd {

  def apply[F[_] : Sync, S, C, E](
    receiveCmd: ReceiveCmd[F, S, C, E]
  ): F[(ReceiveCmd[F, S, C, E], F[List[Action[S, C, E]]])] = {

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
            new Validate[F, S, E] {
              def apply(state: S, seqNr: SeqNr) = {
                for {
                  directive <- validate(state, seqNr)
                  _         <- actions.add(Action.Directive(directive.change))
                } yield {
                  directive
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

    final case class Directive[S, E](change: Option[eventsourcing.Change[S, E]]) extends Action[S, Nothing, E]
  }
}
