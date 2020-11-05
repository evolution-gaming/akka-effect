package com.evolutiongaming.akkaeffect.util

import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class SerialRefTest extends AsyncFunSuite with Matchers {

  test("update") {
    update[IO].run()
  }

  private def update[F[_]: Concurrent: FromFuture: ToFuture]: F[Unit] = {
    for {
      serialRef <- SerialRef[F].of(List.empty[Int])
      ref       <- Ref[F].of(List.empty[Int])
      update     = (f: List[Int] => F[List[Int]]) => serialRef.update { a => f(a).flatTap { b => ref.set(b) } }
      deferred  <- Deferred[F, Int]
      _         <- update { a => deferred.get.map { _ :: a } }
      f         <- (1 to 9).toList.foldLeftM(().pure[F]) { case (_, a) => update { as => (a :: as).pure[F] } }
      a         <- ref.get
      _          = a shouldEqual List.empty
      _         <- deferred.complete(0)
      _         <- f
      a         <- ref.get
      _          = a shouldEqual (0 to 9).toList.reverse
    } yield {}
  }
}
