package com.evolutiongaming.akkaeffect

import cats.syntax.all._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class ExtractTest extends AnyFunSuite with Matchers {

  List(
    ("s", none[Either[String, Long]]),
    ("s".asLeft, "s".asLeft[Long].some),
    ("s".asRight, none[Either[String, Long]]),
    (0L, none[Either[String, Long]]),
    (0L.asRight, 0L.asRight[String].some),
    (0L.asLeft, none[Either[String, Long]]),
    (1, none[Either[String, Long]]),
    (1.asRight, none[Either[String, Long]]),
    (1.asLeft, none[Either[String, Long]])
  ).foreach {
    case (a, expected) =>
      test(s"either $a") {
        implicit val extractStr  = Extract.fromClassTag[Try, String]
        implicit val extractLong = Extract.fromClassTag[Try, Long]
        val extractToJsonAble    = Extract.either[Try, String, Long]
        extractToJsonAble(a) shouldEqual expected.toOptionT[Try]
      }
  }

  test("orElse") {
    val extract = Extract.fromClassTag[Try, String] orElse Extract.fromClassTag[Try, Int].map(_.toString)
    extract(1) shouldEqual "1".some.toOptionT[Try]
    extract("1") shouldEqual "1".some.toOptionT[Try]
  }
}
