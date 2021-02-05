package com.evolutiongaming.akkaeffect

import cats.effect.IO
import cats.syntax.all._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import scala.util.control.NoStackTrace

class ActTest extends AnyFunSuite with Matchers {

  test("adapter") {
    var msg = none[Any]
    val tell = (a: Any) => msg = a.some
    val act = Act.Adapter[IO](tell)
    act
      .sync { act.value { 0 } }
      .toFuture
      .value shouldEqual 0.pure[Try].some

    val future = act.value { 0 }.toFuture
    future.value shouldEqual none

    val receive = act.receive(PartialFunction.empty)

    msg.foreach { receive.lift }

    future.value shouldEqual 0.pure[Try].some

    case object Error extends RuntimeException with NoStackTrace
    intercept[Error.type] { act.sync { act.value { throw Error }.toTry.get } }
  }
}
