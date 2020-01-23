package com.evolutiongaming.akkaeffect

import cats.effect.IO
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.FromFuture
import org.scalatest.Succeeded
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise, TimeoutException}
import scala.util.control.NoStackTrace

class StateVarTest extends AsyncFunSuite with Matchers {

  test("update success") {
    val state = StateVar[IO].of(0)

    def increment() = {
      val promise = Promise[Int]
      state.update { a =>
        val b = a + 1
        IO { promise.success(b) }.as(b)
      }
      promise.future
    }

    val futures = (1 to 100).map { expected =>
      increment().map { _ shouldEqual expected }
    }
    Future
      .sequence(futures)
      .map { _ => Succeeded }
  }

  test("update failure") {
    val error = new RuntimeException with NoStackTrace
    val state = StateVar[IO].of(())

    state.update { _ => error.raiseError[IO, Unit] }

    val promise = Promise[Unit]
    state.update { _ => IO { promise.success(()) } }

    FromFuture[IO]
      .apply { promise.future }
      .timeout(100.millis)
      .attempt
      .map { _ should matchPattern { case Left(_: TimeoutException) => } }
      .toFuture
  }
}
