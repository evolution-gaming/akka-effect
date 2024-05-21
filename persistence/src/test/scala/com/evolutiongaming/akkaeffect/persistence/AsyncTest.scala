package com.evolutiongaming.akkaeffect.persistence

import cats.effect.{Async, IO, Sync}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class AsyncTest extends AsyncFunSuite with Matchers {

  test("async") {
    async[IO].run()
  }

  private def async[F[_]: Async] = {
    def threadId() = Thread.currentThread().getId
    for {
      a <- Sync[F].delay(threadId())
      b <- Async[F].async_[Long](callback => callback(threadId().asRight))
      c <- Sync[F].delay(threadId())
      _ <- Sync[F].delay(a shouldEqual b)
      _ <- Sync[F].delay(b shouldEqual c)
    } yield {}
  }
}
