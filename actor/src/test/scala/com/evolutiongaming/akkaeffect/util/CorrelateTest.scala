package com.evolutiongaming.akkaeffect.util

import cats.effect.IO
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.Correlate
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NoStackTrace

// format: off
class CorrelateTest extends AsyncFunSuite with Matchers {

  private val timeout = 1.minute

  test("correlate") {

    val error = new RuntimeException with NoStackTrace

    val result = for {
      a <- Correlate
        .of[IO, Int, String]("stopped".pure[IO])
        .use { correlate =>
          for {
            a0 <- correlate.call(0, timeout)
            a1 <- correlate.call(1, timeout)
            a2 <- correlate.call(2, timeout)
            a3 <- correlate.call(3, timeout)
            a4 <- correlate.call(4, 1.millis)
            a  <- a4.attempt
            _   = a should matchPattern { case Left(_: TimeoutException) => }
            b  <- correlate.callback(0, "0".asRight)
            _   = b shouldEqual true
            b  <- correlate.callback(0, "0".asRight)
            _   = b shouldEqual false
            a  <- a0
            _   = a shouldEqual "0"
            _  <- correlate.callback(1, "1".asRight)
            a  <- a1
            _   = a shouldEqual "1"
            _  <- correlate.callback(2, error.asLeft)
            a  <- a2.attempt
            _   = a shouldEqual error.asLeft
          } yield a3
        }
      a <- a
      _ = a shouldEqual "stopped"
    } yield {}
    result.run()
  }


  test("unsafe") {
    val (correlate, release) = Correlate.Unsafe.of[Int, String]("stopped".pure[Try])
    val future = correlate.call(0, timeout)
    correlate.callback(0, "0".asRight) shouldEqual true
    correlate.callback(0, "0".asRight) shouldEqual false
    future.map { value =>
      release()
      value shouldEqual "0"
    }
  }
}
