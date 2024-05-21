package com.evolutiongaming.akkaeffect.util

import cats.effect.IO
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class CloseOnErrorTest extends AsyncFunSuite with Matchers {

  test("close on error") {
    val error: Throwable = new RuntimeException with NoStackTrace
    val result = for {
      closeOnError <- CloseOnError.of[IO]
      e            <- closeOnError.error
      _             = e shouldEqual none
      a            <- closeOnError(0.pure[IO])
      _             = a shouldEqual 0
      a            <- closeOnError(error.raiseError[IO, Unit]).attempt
      _             = a shouldEqual error.asLeft
      e            <- closeOnError.error
      _             = e shouldEqual error.some
      a            <- closeOnError(0.pure[IO]).attempt
      _             = a shouldEqual error.asLeft
      e            <- closeOnError.error
      _             = e shouldEqual error.some
    } yield {}
    result.run()
  }
}
