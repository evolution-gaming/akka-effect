package com.evolutiongaming.akkaeffect.util

import cats.effect.IO
import cats.effect.kernel.Deferred
import org.scalatest.funsuite.AsyncFunSuite
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class SeriallyTest extends AsyncFunSuite with Matchers {

  test("error") {
    val result = for {
      error    <- IO(new RuntimeException with NoStackTrace)
      serially <- IO(Serially[IO, Int](0))
      value    <- IO.defer(serially(_ => error.raiseError[IO, Int])).attempt
      _        <- IO(value shouldEqual error.asLeft)
      deferred <- Deferred[IO, Int]
      _        <- IO.defer(serially(a => deferred.complete(a).as(a)))
      value    <- deferred.get
      _        <- IO(value shouldEqual 0)
    } yield {}
    result.run()
  }

  test("handle concurrently added tasks serially") {
    val n = 10000
    var i = 0
    val result = for {
      serially <- IO(Serially[IO, Int](0))
      _ <- serially
        .apply { a =>
          IO
            .apply { i = i + 1 }
            .as(a + 1)
        }
        .parReplicateA(n)
      _ <- IO(i shouldEqual n)
    } yield ()
    result.run()
  }
}
