package akka.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import cats.syntax.all._
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, SeqNr, Event, Events, Snapshotter}
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem

import scala.util.Random
import akka.persistence.journal.AsyncWriteJournal
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try
import akka.persistence.snapshot.SnapshotStore
import akka.pattern.AskTimeoutException
import java.util.concurrent.TimeoutException

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

  test("snapshot: fail load snapshot") {

    val pluginId      = "failing-snapshot"
    val persistenceId = EventSourcedId("#3")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
          snapshot    <- snapshotter.load(SnapshotSelectionCriteria(), Long.MaxValue)
          error       <- snapshot.attempt
          _            = error shouldEqual Expected.exception.asLeft[List[Event[String]]]
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
          saving      <- snapshotter.save(SeqNr.Min, payload)
          error       <- saving.attempt
          _            = error shouldEqual Expected.exception.asLeft[List[Event[String]]]
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
          deleting    <- snapshotter.delete(SeqNr.Min)
          error       <- deleting.attempt
          _            = error shouldEqual Expected.exception.asLeft[List[Event[String]]]
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
          deleting    <- snapshotter.delete(Snapshotter.Criteria())
          error       <- deleting.attempt
          _            = error shouldEqual Expected.exception.asLeft[List[Event[String]]]
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
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
          adapter     <- PersistenceAdapter.of[IO](system, 1.second)
          snapshotter <- adapter.snapshotter[String](pluginId, persistenceId)
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

  test("journal: replay (nothing), save, replay, delete, replay") {

    val persistenceId = EventSourcedId("#11")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter <- PersistenceAdapter.of[IO](system, 600.second)
          journal <- adapter.journaller[String](emptyPluginId, persistenceId, SeqNr.Min)
          events  <- journal.replay(SeqNr.Max, Long.MaxValue)
          events  <- events.toList
          _        = events shouldEqual List.empty[Event[String]]
          seqNr   <- journal.append(Events.of("first", "second")).flatten
          _        = seqNr shouldEqual 2L
          events  <- journal.replay(SeqNr.Max, Long.MaxValue)
          events  <- events.toList
          _        = events shouldEqual List(Event.const("first", 1L), Event.const("second", 2L))
          _       <- journal.deleteTo(1L).flatten
          events  <- journal.replay(SeqNr.Max, Long.MaxValue)
          events  <- events.toList
          _        = events shouldEqual List(Event.const("second", 2L))
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("journal: fail loading events") {

    val pluginId      = "failing-journal"
    val persistenceId = EventSourcedId("#11")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter <- PersistenceAdapter.of[IO](system, 1.second)
          journal <- adapter.journaller[String](pluginId, persistenceId, SeqNr.Min)
          events  <- journal.replay(SeqNr.Max, Long.MaxValue)
          error   <- events.toList.attempt
          _        = error shouldEqual Expected.exception.asLeft[List[Event[String]]]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("journal: fail persisting events") {

    val pluginId      = "failing-journal"
    val persistenceId = EventSourcedId("#12")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter <- PersistenceAdapter.of[IO](system, 1.second)
          journal <- adapter.journaller[String](pluginId, persistenceId, SeqNr.Min)
          seqNr   <- journal.append(Events.of[String]("first", "second"))
          error   <- seqNr.attempt
          _        = error shouldEqual Expected.exception.asLeft[SeqNr]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("journal: fail deleting events") {

    val pluginId      = "failing-journal"
    val persistenceId = EventSourcedId("#13")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter  <- PersistenceAdapter.of[IO](system, 1.second)
          journal  <- adapter.journaller[String](pluginId, persistenceId, SeqNr.Min)
          deleting <- journal.deleteTo(SeqNr.Max)
          error    <- deleting.attempt
          _         = error shouldEqual Expected.exception.asLeft[Unit]
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("journal: timeout on loading events") {

    val pluginId      = "infinite-journal"
    val persistenceId = EventSourcedId("#14")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter <- PersistenceAdapter.of[IO](system, 1.second)
          journal <- adapter.journaller[String](pluginId, persistenceId, SeqNr.Min)
          events  <- journal.replay(SeqNr.Max, Long.MaxValue)
          error   <- events.toList.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r)                  => fail(s"the test should fail with TimeoutException while actual result is $r")
        }
      }

    io.unsafeRunSync()
  }

  test("journal: timeout persisting events") {

    val pluginId      = "infinite-journal"
    val persistenceId = EventSourcedId("#15")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter <- PersistenceAdapter.of[IO](system, 1.second)
          journal <- adapter.journaller[String](pluginId, persistenceId, SeqNr.Min)
          seqNr   <- journal.append(Events.of[String]("first", "second"))
          error   <- seqNr.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r)                  => fail(s"the test should fail with TimeoutException while actual result is $r")
        }
      }

    io.unsafeRunSync()
  }

  test("journal: timeout deleting events") {

    val pluginId      = "infinite-journal"
    val persistenceId = EventSourcedId("#16")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          adapter  <- PersistenceAdapter.of[IO](system, 1.second)
          journal  <- adapter.journaller[String](pluginId, persistenceId, SeqNr.Min)
          deleting <- journal.deleteTo(SeqNr.Max)
          error    <- deleting.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r)                  => fail(s"the test should fail with TimeoutException while actual result is $r")
        }
      }

    io.unsafeRunSync()
  }
}

object Expected {
  val exception = new RuntimeException("test exception")
}

class FailingJournal extends AsyncWriteJournal {

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = Future.failed(Expected.exception)

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = Future.failed(Expected.exception)

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = Future.failed(Expected.exception)

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = Future.failed(Expected.exception)

}

class InfiniteJournal extends AsyncWriteJournal {

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = Future.never

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = Future.never

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = Future.never

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = Future.never

}

class FailingSnapshotter extends SnapshotStore {

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] =
    Future.failed(Expected.exception)

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = Future.failed(Expected.exception)

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.failed(Expected.exception)

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = Future.failed(Expected.exception)

}

class InfiniteSnapshotter extends SnapshotStore {

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = Future.never

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = Future.never

  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = Future.never

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = Future.never

}
