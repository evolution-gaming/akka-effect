package com.evolutiongaming.akkaeffect.util

import java.util.concurrent.TimeoutException

import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.effect.{Concurrent, IO, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ReadWriteLockTest extends AsyncFunSuite with Matchers {

  ignore("not read during write") {
    `not read during write`[IO].run()
  }

  ignore("not write during read") {
    `not write during read`[IO].run()
  }

  ignore("read in parallel") {
    `read in parallel`[IO].run()
  }

  ignore("write serially") {
    `write serially`[IO].run()
  }

  ignore("read and write") {
    `read and write`[IO].run()
  }


  def `not read during write`[F[_]: Concurrent: Timer]: F[Unit] = {
    for {
      lock <- ReadWriteLock.of[F]
      d    <- Deferred[F, Unit]
      w    <- lock.write(d.get)
      r    <- lock.read(().pure[F])
      a    <- r.timeout(10.millis).attempt
      _     = a should matchPattern { case Left(_: TimeoutException) => }
      _    <- d.complete(())
      _    <- w
      _    <- r
    } yield {}
  }


  def `not write during read`[F[_]: Concurrent: Timer]: F[Unit] = {
    for {
      lock <- ReadWriteLock.of[F]
      d    <- Deferred[F, Unit]
      w    <- lock.read(d.get)
      r    <- lock.write(().pure[F])
      a    <- r.timeout(10.millis).attempt
      _     = a should matchPattern { case Left(_: TimeoutException) => }
      _    <- d.complete(())
      _    <- r
      _    <- w
    } yield {}
  }


  def `read in parallel`[F[_]: Concurrent: Timer]: F[Unit] = {
    for {
      lock <- ReadWriteLock.of[F]
      d    <- Deferred[F, Unit]
      d0   <- Deferred[F, Unit]
      d1   <- Deferred[F, Unit]
      r0   <- lock.read { d0.complete(()) *> d.get }
      r1   <- lock.read { d1.complete(()) *> d.get }
      _    <- d0.get
      _    <- d1.get
      _    <- d.complete(())
      _    <- r1
      _    <- r0
    } yield {}
  }


  def `write serially`[F[_]: Concurrent: Timer]: F[Unit] = {
    for {
      lock <- ReadWriteLock.of[F]
      d    <- Deferred[F, Unit]
      w0   <- lock.write { d.get }
      w1   <- lock.write { ().pure[F] }
      w    <- w1.timeout(10.millis).attempt
      _     = w should matchPattern { case Left(_: TimeoutException) => }
      _    <- d.complete(())
      _    <- lock.write { ().pure[F] }.flatten
      _    <- w0
      _    <- w1
    } yield {}
  }


  def `read and write`[F[_]: Concurrent: Timer]: F[Unit] = {
    for {
      lock <- ReadWriteLock.of[F]
      d    <- Deferred[F, Unit]
      w0   <- lock.write { d.get }
      w1   <- lock.write { ().pure[F] }
      w    <- w1.timeout(10.millis).attempt
      _     = w should matchPattern { case Left(_: TimeoutException) => }
      _    <- d.complete(())
      _    <- lock.write { ().pure[F] }.flatten
      _    <- w0
      _    <- w1
    } yield {}
  }
}
