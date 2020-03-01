package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, IO}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.catshelper.ToFuture
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class ReceiveFromReceiveCmdTest extends AsyncFunSuite with Matchers {

  test("happy path") {
    `happy path`[IO].run()
  }


  private def `happy path`[F[_] : Concurrent/*TODO remove*/ : ToFuture]: F[Unit] ={

    type S = Any
    case class C(events: Nel[Nel[E]])
//    type E = Any
    case class E(value: F[Unit])
    type R = Any

    val append = for {
      ref <- Ref[F].of(0L)
    } yield {
      new Append[F, E] {

        def apply(events: Nel[Nel[E]]) = {
          val size = events.foldMap { _.size }
          for {
            b <- ref.modify { a =>
              val b = a + size
              (b, b)
            }
          } yield {
            events
              .traverse { _.traverse { _.value } }
              .as(b)
          }
        }
      }
    }

    val receiveCmd: ReceiveCmd[F, S, C, E] = new ReceiveCmd[F, S, C, E] {
      def apply(cmd: C) = {
        val phase = new Validate[F, S, C, E] {
          def apply(state: S, seqNr: SeqNr) = {
            val result = CmdResult.Change(state, cmd.events, error => ().pure[F])
            result.pure[F]
          }
        }
        phase.pure[F]
      }
    }

    for {
      append <- append
      result <- ReceiveFromReceiveCmd[F, S, C, E, R](0, 0L, append, receiveCmd).use { receive =>
        ().pure[F]
      }
    } yield result
  }
}

object ReceiveFromReceiveCmdTest {

}
