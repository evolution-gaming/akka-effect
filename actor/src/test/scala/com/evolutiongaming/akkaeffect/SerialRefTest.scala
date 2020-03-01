package com.evolutiongaming.akkaeffect

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, IO}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class SerialRefTest extends AsyncFunSuite with Matchers {

  test("update") {
    update[IO].run()
  }

  private def update[F[_] : Concurrent : FromFuture : ToFuture]: F[Unit] = {
    for {
      serialRef <- SerialRef[F].of(List.empty[Int])
      deferred  <- Deferred[F, Int]
      _         <- serialRef.update { a => deferred.get.map { _ :: a } }
      f         <- (1 to 9).toList.foldLeftM(().pure[F]) { case (_, a) => serialRef.update { as => (a :: as).pure[F] } }
      a         <- serialRef.get
      _          = a shouldEqual List.empty
      _         <- deferred.complete(0)
      _         <- f
      a         <- serialRef.get
      _          = a shouldEqual (0 to 9).toList.reverse
    } yield {}
  }
}
