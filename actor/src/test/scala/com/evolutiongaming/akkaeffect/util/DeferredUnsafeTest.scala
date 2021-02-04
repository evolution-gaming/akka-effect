package com.evolutiongaming.akkaeffect.util

import cats.effect.IO
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers


class DeferredUnsafeTest extends AsyncFunSuite with Matchers {

  test("async boundaries") {
    val threadId = IO { Thread.currentThread().getId }
    val result = for {
      d  <- IO { DeferredUnsafe[IO, Unit] }
      t0 <- threadId
      _  <- d.complete(())
      t1 <- threadId
      _  <- IO { t0 shouldEqual t1 }
      _  <- d.get
      t1 <- threadId
      _  <- IO { t0 shouldEqual t1 }
      d  <- IO { DeferredUnsafe[IO, Unit] }
      t1 <- d.get.flatMap { _ => threadId }.start
      _  <- d.complete(())
      t1 <- t1.join
      _  <- IO { t0 shouldEqual t1 }
    } yield {}
    result.run()
  }
}