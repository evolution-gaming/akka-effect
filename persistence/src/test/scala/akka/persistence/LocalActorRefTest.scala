package akka.persistence

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite

class LocalActorRefTest extends AnyFunSuite with Matchers {

  val poison = 100500

  def of = LocalActorRef[IO, Int, Int](0) {
    case (s, `poison`) => IO(s.asRight)
    case (s, m: Int)   => IO((s + m).asLeft)
  }

  test("LocalActorRef can handle messages and produce result") {
    val io = for {
      r <- of
      n <- r.get
      _  = n shouldEqual none
      _  = r.ref ! 3
      _  = r.ref ! 4
      _  = r.ref ! poison
      r <- r.res
      _  = r shouldEqual 7
    } yield {}

    io.unsafeRunSync()
  }

  test("LocalActorRef.res semantically blocks until result produced") {
    val io = for {
      d  <- IO.deferred[Unit]
      r  <- of
      f   = r.res >> d.complete {}
      f  <- f.start
      d0 <- d.tryGet
      _   = d0 shouldEqual none
      _   = r.ref ! poison
      _  <- f.join
      d1 <- d.tryGet
      _   = d1 shouldEqual {}.some
    } yield {}

    io.unsafeRunSync()
  }

  test("LocalActorRef should handle concurrent ! operations") {
    val io = for {
      r <- of
      l  = List.range(0, 100)
      _ <- l.parTraverse(i => IO(r.ref ! i))
      _  = r.ref ! poison
      r <- r.res
      _  = r shouldEqual l.sum
    } yield {}

    io.unsafeRunSync()
  }

}
