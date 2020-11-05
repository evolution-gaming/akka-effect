package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}
import cats.syntax.all._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class EventsTest extends AnyFunSuite with Matchers {

  test("show") {
    Events.attached(1, 2).show shouldEqual "Events(1,2)"
    Events.detached(1, 2).show shouldEqual "Events([1],[2])"
    Events.batched(Nel.of(1, 2), Nel.of(3, 4)).show shouldEqual "Events([1,2],[3,4])"
  }

  test("toString") {
    Events.attached(1, 2).toString shouldEqual "Events(1,2)"
    Events.detached(1, 2).toString shouldEqual "Events([1],[2])"
    Events.batched(Nel.of(1, 2), Nel.of(3, 4)).toString shouldEqual "Events([1,2],[3,4])"
  }

  test("prepend") {
    Events.of(2).prepend(1) shouldEqual Events.detached(1, 2)
  }

  test("append") {
    Events.of(1).append(2) shouldEqual Events.detached(1, 2)
  }
}
