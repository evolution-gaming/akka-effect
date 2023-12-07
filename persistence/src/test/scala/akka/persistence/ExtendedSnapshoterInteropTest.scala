package akka.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, SeqNr, Event, Snapshotter}
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem

import scala.util.Random

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.persistence.snapshot.SnapshotStore
import akka.pattern.AskTimeoutException

class ExtendedSnapshoterInteropTest extends AnyFunSuite with Matchers {

  val emptyPluginId = ""

  test("snapshot: load, save and load again") {

    val persistenceId = EventSourcedId("#1")
    val payload       = Random.nextString(1024)

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](emptyPluginId, persistenceId)
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
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](emptyPluginId, persistenceId)
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

  test("snapshot: fail load snapshot") {

    val pluginId      = "failing-snapshot"
    val persistenceId = EventSourcedId("#3")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue)
          error       <- snapshot.attempt
          _            = error shouldEqual FailingSnapshotter.exception.asLeft[List[Event[String]]]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("snapshot: fail save snapshot") {

    val pluginId      = "failing-snapshot"
    val persistenceId = EventSourcedId("#4")
    val payload       = Random.nextString(1024)

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          saving      <- snapshotter.save(SeqNr.Min, payload)
          error       <- saving.attempt
          _            = error shouldEqual FailingSnapshotter.exception.asLeft[List[Event[String]]]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("snapshot: fail delete snapshot") {

    val pluginId      = "failing-snapshot"
    val persistenceId = EventSourcedId("#5")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          deleting    <- snapshotter.delete(SeqNr.Min)
          error       <- deleting.attempt
          _            = error shouldEqual FailingSnapshotter.exception.asLeft[List[Event[String]]]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("snapshot: fail delete snapshot by criteria") {

    val pluginId      = "failing-snapshot"
    val persistenceId = EventSourcedId("#6")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          deleting    <- snapshotter.delete(Snapshotter.Criteria())
          error       <- deleting.attempt
          _            = error shouldEqual FailingSnapshotter.exception.asLeft[List[Event[String]]]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("snapshot: timeout loading snapshot") {

    val pluginId      = "infinite-snapshot"
    val persistenceId = EventSourcedId("#7")

    val io = TestActorSystem[IO]("testing", none)
      .use(system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue)
          error       <- snapshot.attempt
        } yield error match {
          case Left(_: AskTimeoutException) => succeed
          case Left(other)                  => fail(other)
          case Right(_)                     => fail("the test should fail with AskTimeoutException but did no")
        }
      )

    io.unsafeRunSync()
  }

  test("snapshot: timeout saving snapshot") {

    val pluginId      = "infinite-snapshot"
    val persistenceId = EventSourcedId("#8")
    val payload       = Random.nextString(1024)

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          saving      <- snapshotter.save(SeqNr.Min, payload)
          error       <- saving.attempt
        } yield error match {
          case Left(_: AskTimeoutException) => succeed
          case Left(other)                  => fail(other)
          case Right(_)                     => fail("the test should fail with AskTimeoutException but did no")
        }
      }

    io.unsafeRunSync()
  }

  test("snapshot: timeout deleting snapshot") {

    val pluginId      = "infinite-snapshot"
    val persistenceId = EventSourcedId("#9")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          deleting    <- snapshotter.delete(SeqNr.Min)
          error       <- deleting.attempt
        } yield error match {
          case Left(_: AskTimeoutException) => succeed
          case Left(other)                  => fail(other)
          case Right(_)                     => fail("the test should fail with AskTimeoutException but did no")
        }
      }

    io.unsafeRunSync()
  }

  test("snapshot: timeout deleting snapshot by criteria") {

    val pluginId      = "infinite-snapshot"
    val persistenceId = EventSourcedId("#10")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          of          <- ExtendedSnapshoterInterop[IO](system, 1.second)
          snapshotter <- of[String](pluginId, persistenceId)
          deleting    <- snapshotter.delete(Snapshotter.Criteria())
          error       <- deleting.attempt
        } yield error match {
          case Left(_: AskTimeoutException) => succeed
          case Left(other)                  => fail(other)
          case Right(_)                     => fail("the test should fail with AskTimeoutException but did no")
        }
      }

    io.unsafeRunSync()
  }

}

object FailingSnapshotter {

  val exception = new RuntimeException("test exception")

}

class FailingSnapshotter extends SnapshotStore {

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future.failed(FailingSnapshotter.exception)

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = Future.failed(FailingSnapshotter.exception)

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.failed(FailingSnapshotter.exception)

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    Future.failed(FailingSnapshotter.exception)

}

class InfiniteSnapshotter extends SnapshotStore {

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = Future.never

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = Future.never

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.never

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = Future.never

}
