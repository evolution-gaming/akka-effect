package com.evolutiongaming.akkaeffect.persistence

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.{Act, ActorSuite}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import scala.util.control.NoStackTrace

class CallTest extends AsyncFunSuite with Matchers {

  test("adapter") {

    case class Msg(key: Int, result: Try[String])

    val error = new RuntimeException with NoStackTrace

    val result = for {
      ref     <- Ref[IO].of(0)
      stopped  = ref.update { _ + 1 }.as("stopped")
      a       <- Call
        .adapter[IO, Int, String](Act.now, stopped) { case Msg(a, b) => (a, b) }
        .use { adapter =>

          def call(key: Int): IO[IO[String]] = {
            for {
              ak     <- adapter.value(key)
              (k, a)  = ak
              _       = k shouldEqual key
            } yield a
          }

          for {
            a0 <- call { 0 }
            a1 <- call { 1 }
            a2 <- call { 2 }
            a3 <- call { 3 }
            _  <- IO { adapter.receive.lift(Msg(0, "0".pure[Try])) }
            _  <- IO { adapter.receive.lift(Msg(1, "1".pure[Try])) }
            _  <- IO { adapter.receive.lift(Msg(2, error.raiseError[Try, String])) }
            _  <- IO { adapter.receive.lift(Msg(2, "2".pure[Try])) }
            a  <- a0
            _   = a shouldEqual "0"
            a  <- a1
            _   = a shouldEqual "1"
            a  <- a2.attempt
            _   = a shouldEqual error.asLeft
          } yield a3
        }
      a       <- a
      _        = a shouldEqual "stopped"
    } yield {}
    result.run()
  }
}
