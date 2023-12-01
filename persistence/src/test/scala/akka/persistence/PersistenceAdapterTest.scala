package akka.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, SeqNr}
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import scala.concurrent.duration._

import scala.util.Random

class PersistenceAdapterTest extends AnyFunSuite with Matchers {

  val emptyPluginId = ""

  test("snapshot: load, save and load again") {

    val persistenceId = EventSourcedId("#1")
    val payload       = Random.nextString(1024)

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](emptyPluginId, persistenceId)
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue).flatten
          _            = snapshot shouldEqual none
          _           <- snapshotter.save(SeqNr.Min, payload).flatten
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue).flatten
          _            = snapshot.get.snapshot should equal(payload)
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("snapshot: save, load, delete and load again") {

    val persistenceId = EventSourcedId("#2")
    val payload       = Random.nextString(1024)

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](emptyPluginId, persistenceId)
          _           <- snapshotter.save(SeqNr.Min, payload).flatten
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue).flatten
          _            = snapshot.get.snapshot should equal(payload)
          _           <- snapshotter.delete(SeqNr.Min).flatten
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue).flatten
          _            = snapshot shouldEqual none
        } yield {}
      }

    io.unsafeRunSync()
  }
}
