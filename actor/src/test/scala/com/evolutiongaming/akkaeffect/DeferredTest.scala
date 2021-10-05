package com.evolutiongaming.akkaeffect

import cats.effect.IO
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers
import cats.effect.Deferred

class DeferredTest extends AsyncFunSuite with Matchers {

  test("cancelable") {
    val result = for {
      deferred <- Deferred[IO, Unit]
      _         = verify(deferred, sync = false)
    } yield ()
    result.run()
  }

  test("uncancelable") {
    val result = for {
      deferred <- Deferred.uncancelable[IO, Unit]
      _         = verify(deferred, sync = true)
    } yield ()
    result.run()
  }

  private def verify(deferred: Deferred[IO, Unit], sync: Boolean) = {
    val result = for {
      _      <- deferred.get.startEnsure
      future <- IO { deferred.complete(()).toFuture }
      _       = future.isCompleted shouldEqual sync
    } yield ()
    result.run()
  }
}
