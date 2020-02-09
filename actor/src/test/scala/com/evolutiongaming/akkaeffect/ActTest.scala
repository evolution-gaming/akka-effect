package com.evolutiongaming.akkaeffect

import cats.implicits._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class ActTest extends AnyFunSuite with Matchers {

  test("adapter") {
    var msg = none[Any]
    val tell = (a: Any) => msg = a.some
    val act = Act.adapter(tell)
    act
      .sync { act.value { 0 } }
      .value shouldEqual 0.pure[Try].some

    val future = act.value { 0 }
    future.value shouldEqual none

    val receive = act.receive(PartialFunction.empty)

    msg.foreach { receive.lift }

    future.value shouldEqual 0.pure[Try].some
  }
}
