package com.evolutiongaming.akkaeffect.util

import cats.effect.IO
import cats.effect.kernel.Deferred
import org.scalatest.funsuite.AsyncFunSuite
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class SeriallyTest extends AsyncFunSuite with Matchers {

  test("serially") {
    val threadId = IO { Thread.currentThread().getId }
    val result = for {
      serially  <- IO { Serially[IO, Int](0) }
      threadId0 <- threadId
      _         <- serially { a => (a + 1).pure[IO] }
      threadId1 <- threadId
      _         <- IO { threadId0 shouldEqual threadId1 }
      deferred  <- Deferred[IO, Int]
      _         <- IO.defer { serially { a => deferred.complete(a).as(a) } }
      value     <- deferred.get
      _         <- IO { value shouldEqual 1 }
      deferred  <- Deferred[IO, Unit]
      threadId0 <- Deferred[IO, Long]
      threadId1 <- Deferred[IO, Long]
      _         <- serially { a =>
        for {
          _        <- deferred.get
          threadId <- threadId
          _        <- threadId0.complete(threadId)
        } yield {
          a + 1
        }
      }
        .start
      _         <- serially { a =>
        for {
          threadId <- threadId
          _        <- threadId1.complete(threadId)
        } yield {
          a + 1
        }
      }.start
      _         <- deferred.complete(())
      threadId0 <- threadId0.get
      threadId1 <- threadId1.get
      _         <- IO { threadId0 shouldEqual threadId1 }
      deferred  <- Deferred[IO, Int]
      _         <- IO.defer { serially { a => deferred.complete(a).as(a) } }
      value     <- deferred.get
      _         <- IO { value shouldEqual 3 }
    } yield {}
    result.run()
  }

  test("error") {
    val result = for {
      error     <- IO { new RuntimeException with NoStackTrace }
      serially  <- IO { Serially[IO, Int](0) }
      value     <- IO.defer { serially { _ => error.raiseError[IO, Int] } }.attempt
      _         <- IO { value shouldEqual error.asLeft }
      deferred  <- Deferred[IO, Int]
      _         <- IO.defer { serially { a => deferred.complete(a).as(a) } }
      value     <- deferred.get
      _         <- IO { value shouldEqual 0 }
    } yield {}
    result.run()
  }

  test("handles many concurrent tasks") {
    var i = 0

    val result = for {
      serially <- IO { Serially[IO, Int](0) }
      _ <- serially.apply(s => IO {
        i = i + 1
      }.as(s + 1)).parReplicateA(100000)
      _ <- IO { i shouldEqual 100000 }
    } yield ()
    result.run()
  }
}
