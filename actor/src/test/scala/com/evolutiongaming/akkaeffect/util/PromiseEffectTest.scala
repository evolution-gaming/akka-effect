package com.evolutiongaming.akkaeffect.util

import cats.effect.IO
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import scala.util.control.NoStackTrace

class PromiseEffectTest extends AsyncFunSuite with Matchers {

  test("success") {
    val result = for {
      p <- PromiseEffect[IO, Int]
      a <- p.get.startEnsure
      _ <- p.success(0)
      a <- a.join
      _  = a shouldEqual 0
    } yield {}
    result.run()
  }

  test("fail") {
    val error = new RuntimeException with NoStackTrace
    val result = for {
      promise <- PromiseEffect[IO, Int]
      a <- promise.get.startEnsure
      _ <- promise.fail(error)
      a <- a.join.attempt
      _  = a shouldEqual error.asLeft
    } yield {}
    result.run()
  }

  test("complete") {
    val result = for {
      p <- PromiseEffect[IO, Int]
      f <- IO { p.complete(0.pure[Try]).toFuture }
      _  = f.value shouldEqual ().pure[Try].some
      a <- p.get
      _  = a shouldEqual 0
    } yield {}
    result.run()
  }
}
