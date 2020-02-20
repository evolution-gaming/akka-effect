package com.evolutiongaming.akkaeffect

import cats.effect.IO
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.Failure

class ToTryFromToFutureTest extends AnyFunSuite with Matchers {

  test("syncOrError") {
    implicit val toTry = ToTryFromToFuture.syncOrError[IO]
    IO.sleep(1.second).toTry should matchPattern { case Failure(_: AsyncEffectError) => }
  }
}
