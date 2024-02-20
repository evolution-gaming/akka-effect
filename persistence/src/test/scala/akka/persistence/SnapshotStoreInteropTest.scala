package akka.persistence

import akka.pattern.AskTimeoutException
import cats.effect.IO
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.EventSourcedId
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.akkaeffect.persistence.SnapshotStore
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

class SnapshotStoreInteropTest extends AnyFunSuite with Matchers {

  val emptyPluginId = ""

  test("snapshot: load, save and load again") {

    val persistenceId = EventSourcedId("#1")
    val payload       = Random.nextString(1024)

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          store    <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, emptyPluginId, persistenceId)
          snapshot <- store.latest
          _         = snapshot shouldEqual none
          _        <- store.save(SeqNr.Min, payload).flatten
          snapshot <- store.latest
          _         = snapshot.get.snapshot should equal(payload)
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
          store    <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, emptyPluginId, persistenceId)
          _        <- store.save(SeqNr.Min, payload).flatten
          snapshot <- store.latest
          _         = snapshot.get.snapshot should equal(payload)
          _        <- store.delete(SeqNr.Min).flatten
          snapshot <- store.latest
          _         = snapshot shouldEqual none
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
          store <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          error <- store.latest.attempt
          _      = error shouldEqual FailingSnapshotter.exception.asLeft[List[SnapshotStore.Offer[String]]]
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
          store  <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          saving <- store.save(SeqNr.Min, payload)
          error  <- saving.attempt
          _       = error shouldEqual FailingSnapshotter.exception.asLeft[Instant]
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
          store    <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          deleting <- store.delete(SeqNr.Min)
          error    <- deleting.attempt
          _         = error shouldEqual FailingSnapshotter.exception.asLeft[Unit]
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
          store    <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          deleting <- store.delete(SnapshotStore.Criteria())
          error    <- deleting.attempt
          _         = error shouldEqual FailingSnapshotter.exception.asLeft[Unit]
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
          store <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          error <- store.latest.attempt
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
          store  <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          saving <- store.save(SeqNr.Min, payload)
          error  <- saving.attempt
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
          store    <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          deleting <- store.delete(SeqNr.Min)
          error    <- deleting.attempt
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
          store    <- SnapshotStoreInterop[IO, String](Persistence(system), 1.second, pluginId, persistenceId)
          deleting <- store.delete(SnapshotStore.Criteria())
          error    <- deleting.attempt
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

class FailingSnapshotter extends akka.persistence.snapshot.SnapshotStore {

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future.failed(FailingSnapshotter.exception)

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = Future.failed(FailingSnapshotter.exception)

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.failed(FailingSnapshotter.exception)

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] =
    Future.failed(FailingSnapshotter.exception)

}

class InfiniteSnapshotter extends akka.persistence.snapshot.SnapshotStore {

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = Future.never

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = Future.never

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.never

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = Future.never

}
