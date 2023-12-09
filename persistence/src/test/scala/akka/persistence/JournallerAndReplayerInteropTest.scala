package akka.persistence

import akka.persistence.journal.AsyncWriteJournal
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.persistence.Event
import com.evolutiongaming.akkaeffect.persistence.EventSourcedId
import com.evolutiongaming.akkaeffect.persistence.Events
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.util.Try

class JournallerAndReplayerInteropTest extends AnyFunSuite with Matchers {

  val emptyPluginId = ""

  test("journal: replay (nothing), save, replay, delete, replay") {

    val persistenceId = EventSourcedId("#11")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          replayerOf   <- ReplayerInterop[IO](system, 1.second)
          replayer     <- replayerOf[String](emptyPluginId, persistenceId)
          journallerOf <- JournallerInterop[IO](system, 1.second)
          journal      <- journallerOf[String](emptyPluginId, persistenceId, SeqNr.Min)
          events       <- replayer.replay(SeqNr.Min, SeqNr.Max, Long.MaxValue)
          events       <- events.toList
          _             = events shouldEqual List.empty[Event[String]]
          seqNr        <- journal.append(Events.of("first", "second")).flatten
          _             = seqNr shouldEqual 2L
          events       <- replayer.replay(SeqNr.Min, SeqNr.Max, Long.MaxValue)
          events       <- events.toList
          _             = events shouldEqual List(Event.const("first", 1L), Event.const("second", 2L))
          _            <- journal.deleteTo(1L).flatten
          events       <- replayer.replay(SeqNr.Min, SeqNr.Max, Long.MaxValue)
          events       <- events.toList
          _             = events shouldEqual List(Event.const("second", 2L))
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
          replayerOf <- ReplayerInterop[IO](system, 1.second)
          replayer   <- replayerOf[String](pluginId, persistenceId)
          events     <- replayer.replay(SeqNr.Min, SeqNr.Max, Long.MaxValue)
          error      <- events.toList.attempt
        } yield error shouldEqual FailingJournal.exception.asLeft[List[Event[String]]]
      }

    io.unsafeRunSync()
  }

  test("journal: fail persisting events") {

    val pluginId      = "failing-journal"
    val persistenceId = EventSourcedId("#12")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          journallerOf <- JournallerInterop[IO](system, 1.second)
          journal      <- journallerOf[String](pluginId, persistenceId, SeqNr.Min)
          seqNr        <- journal.append(Events.of[String]("first", "second"))
          error        <- seqNr.attempt
        } yield error shouldEqual FailingJournal.exception.asLeft[SeqNr]
      }

    io.unsafeRunSync()
  }

  test("journal: fail deleting events") {

    val pluginId      = "failing-journal"
    val persistenceId = EventSourcedId("#13")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          journallerOf <- JournallerInterop[IO](system, 1.second)
          journal      <- journallerOf[String](pluginId, persistenceId, SeqNr.Min)
          deleting     <- journal.deleteTo(SeqNr.Max)
          error        <- deleting.attempt
        } yield error shouldEqual FailingJournal.exception.asLeft[Unit]
      }

    io.unsafeRunSync()
  }

  test("journal: timeout on loading events") {

    val pluginId      = "infinite-journal"
    val persistenceId = EventSourcedId("#14")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          replayerOf <- ReplayerInterop[IO](system, 1.second)
          replayer   <- replayerOf[String](pluginId, persistenceId)
          events     <- replayer.replay(SeqNr.Min, SeqNr.Max, Long.MaxValue)
          error      <- events.toList.attempt
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
          journallerOf <- JournallerInterop[IO](system, 1.second)
          journal      <- journallerOf[String](pluginId, persistenceId, SeqNr.Min)
          seqNr        <- journal.append(Events.of[String]("first", "second"))
          error        <- seqNr.attempt
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
          journallerOf <- JournallerInterop[IO](system, 1.second)
          journal      <- journallerOf[String](pluginId, persistenceId, SeqNr.Min)
          deleting     <- journal.deleteTo(SeqNr.Max)
          error        <- deleting.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r)                  => fail(s"the test should fail with TimeoutException while actual result is $r")
        }
      }

    io.unsafeRunSync()
  }
}

object FailingJournal {
  val exception = new RuntimeException("test exception")
}

class FailingJournal extends AsyncWriteJournal {

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = Future.failed(FailingJournal.exception)

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future.failed(FailingJournal.exception)

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = Future.failed(FailingJournal.exception)

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = Future.failed(FailingJournal.exception)

}

class InfiniteJournal extends AsyncWriteJournal {

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = Future.never

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = Future.never

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = Future.never

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = Future.never

}
