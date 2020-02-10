package com.evolutiongaming.akkaeffect

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class SeriallyTest extends AsyncFunSuite with Matchers {

  test("serially") {
    val result = for {
      serially <- Serially.of[IO]
      d0       <- Deferred.uncancelable[IO, Unit]
      d1       <- Deferred.uncancelable[IO, Unit]
      ref      <- Ref[IO].of(List.empty[Int])
      a0       <- serially { ref.update { 0 :: _ } *> d0.complete(()) *> d1.get as "0" }
      a1       <- serially { ref.update { 1 :: _ } as "1" }
      _        <- d0.get
      list     <- ref.get
      _         = list shouldEqual List(0)
      _        <- d1.complete(())
      a        <- a0
      _         = a shouldEqual "0"
      a        <- a1
      _         = a shouldEqual "1"
      list     <- ref.get
      _         = list shouldEqual List(1, 0)
    } yield {}
    result.run()
  }

  test("error") {
    val error = new RuntimeException with NoStackTrace
    val result = for {
      serially <- Serially.of[IO]
      a        <- serially { error.raiseError[IO, Unit] }.flatten.attempt
      _         = a shouldEqual error.asLeft
      a        <- serially { "".pure[IO] }.flatten
      _         = a shouldEqual ""
    } yield {}
    result.run()
  }

  test("sync") {
    val result = for {
      serially <- Serially.of[IO]
      fa        = serially { ().pure[IO] }.flatten
      future   <- IO { (fa *> fa).toFuture }
      _         = future.isCompleted shouldEqual true
    } yield {}
    result.run()
  }
}
