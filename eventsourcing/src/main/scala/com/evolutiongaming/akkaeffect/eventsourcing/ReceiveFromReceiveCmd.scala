package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.akkaeffect.{Receive, Reply, Serial, SerialRef}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

// TODO store snapshot in scope of persist queue or expose seqNr
// TODO expose dropped commands because of stop, etc
object ReceiveFromReceiveCmd {

  def apply[F[_]: Concurrent: ToFuture: FromFuture, S, C, E, R](
    state: S,
    seqNr: SeqNr,
    append: Append[F, E],
    receiveCmd: ReceiveCmd[F, S, C, E]
  ): Resource[F, Receive[F, C, R]] = {

    Accelerator
      .of(Accelerator.State(state, seqNr), append)
      .map { accelerator =>
        new Receive[F, C, R] {

          def apply(msg: C, reply: Reply[F, R]) = {

            val result = for {
              validate <- receiveCmd(msg)
              result   <- accelerator(validate)
              result   <- result
            } yield result

            // TODO refactor
            for {
              _ <- result.startNow
            } yield {
              false // TODO wrong
            }
          }
        }
      }
  }
}
