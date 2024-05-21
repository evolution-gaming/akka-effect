package com.evolutiongaming.akkaeffect.util

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AtomicRefTest extends AnyFunSuite with Matchers {

  test("get") {
    val ref = AtomicRef(0)
    ref.get() shouldEqual 0
  }

  test("set") {
    val ref = AtomicRef(0)
    ref.set(1)
    ref.get() shouldEqual 1
  }

  test("getAndSet") {
    val ref = AtomicRef(0)
    ref.getAndSet(1) shouldEqual 0
    ref.get() shouldEqual 1
  }

  test("update") {
    val ref = AtomicRef(0)
    ref.update(_ + 1)
    ref.get() shouldEqual 1
  }

  test("modify") {
    val ref = AtomicRef(0)
    ref.modify(a => (a + 1, a)) shouldEqual 0
    ref.get() shouldEqual 1
  }
}
