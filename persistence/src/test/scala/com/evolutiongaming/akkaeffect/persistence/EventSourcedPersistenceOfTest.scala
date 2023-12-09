package com.evolutiongaming.akkaeffect.persistence

import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.evolutiongaming.akkaeffect.testkit.TestActorSystem

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import com.evolutiongaming.akkaeffect.persistence.SeqNr

class EventSourcedPersistenceOfTest extends AnyFunSuite with Matchers {

  test("recover, append few events, save snapshot, append more events and recover again") {

    val io = TestActorSystem[IO]("testing", none).use { system =>
      val eventSourced = EventSourced[Unit](EventSourcedId("test#1"), value = {})

      for {
        persistenceOf <- EventSourcedPersistenceOf.fromAkka[IO, String, Int](system, 1.second)
        persistence   <- persistenceOf(eventSourced)
        recover       <- persistence.recover
        _              = recover.snapshot shouldEqual none
        events        <- recover.events.toList
        _              = events shouldEqual List.empty
        journaller    <- persistence.journaller(SeqNr.Min)
        seqNr         <- journaller.append(Events.of[Int](1, 2, 3, 4, 5)).flatten
        _              = seqNr shouldEqual 5L
        snapshotter   <- persistence.snapshotter
        at            <- snapshotter.save(seqNr, "snapshot#1").flatten
        seqNr10       <- journaller.append(Events.of[Int](6, 7, 8, 9, 10)).flatten
        _              = seqNr10 shouldEqual 10L
        recover       <- persistence.recover
        _              = recover.snapshot shouldEqual Snapshot.const("snapshot#1", Snapshot.Metadata(seqNr, at)).some
        events        <- recover.events.toList
        _              = events shouldEqual List(6, 7, 8, 9, 10).map(n => Event.const(n, n))
      } yield {}
    }

    io.unsafeRunSync()

  }

}
