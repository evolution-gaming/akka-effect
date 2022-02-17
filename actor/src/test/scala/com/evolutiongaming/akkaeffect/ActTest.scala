package com.evolutiongaming.akkaeffect

import cats.effect.{Deferred, IO}
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.FromFuture
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import scala.util.control.NoStackTrace

class ActTest extends AsyncFunSuite with Matchers {

  test("adapter") {

    case object Error extends RuntimeException with NoStackTrace

    val result = for {
      deferred <- Deferred[IO, Any]
      tell      = (a: Any) => {
        deferred
          .complete(a)
          .toFuture
        ()
      }
      act       = Act.Adapter[IO](tell)
      _        <- IO {
        act
          .sync { act.value { 0 } }
          .toFuture
          .value shouldEqual 0.pure[Try].some
      }
      future   <- IO { act.value { 1 }.toFuture }
      _        <- IO { future.value shouldEqual none }
      msg      <- deferred.get
      receive   = act.receive(PartialFunction.empty)
      _        <- IO { receive.lift(msg) }
      a        <- FromFuture.defer[IO] { future }
      _        <- IO { a shouldEqual 1 }
      a        <- IO { act.sync { act.value { throw Error }.toTry.get } }.attempt
      _        <- IO { a shouldEqual Error.asLeft }
    } yield {}

    result.run()
  }
}
