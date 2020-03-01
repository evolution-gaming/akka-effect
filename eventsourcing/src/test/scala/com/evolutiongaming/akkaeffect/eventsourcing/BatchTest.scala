package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, IO}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers


class BatchTest extends AsyncFunSuite with Matchers {

  test("apply") {
    apply[IO].run()
  }

  private def apply[F[_] : Concurrent]: F[Unit] = {
    for {
      serialBatch <- Batch[F].of(List.empty[Nel[Int]]) { (s, as: Nel[F[Int]]) =>
        for {
          as <- as.sequence
        } yield {
          val s1 = as :: s
          (s1, s1)
        }
      }
      d0 <- Deferred[F, Int]
      _  <- serialBatch(d0.get)
      _  <- serialBatch(1.pure[F])
      d1 <- Deferred[F, Int]
      _  <- serialBatch(d1.get)
      a  <- serialBatch(3.pure[F])
      _  <- d0.complete(0)
      _  <- d1.complete(2)
      a  <- a
      _   = a shouldEqual List(Nel.of(1, 2, 3), Nel.of(0))
    } yield {}
  }
}
