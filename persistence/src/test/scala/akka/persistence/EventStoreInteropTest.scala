package akka.persistence

import akka.persistence.journal.AsyncWriteJournal
import cats.effect.IO
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.EventSourcedId
import com.evolutiongaming.akkaeffect.persistence.EventStore
import com.evolutiongaming.akkaeffect.persistence.Events
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeoutException
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class EventStoreInteropTest extends AnyFunSuite with Matchers {

  val emptyPluginId = ""

  test("journal: replay (nothing), save, replay, delete, replay") {

    val persistenceId = EventSourcedId("#10")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          store  <- EventStoreInterop[IO, String](system, 1.second, 100, emptyPluginId, persistenceId)
          events <- store.events(SeqNr.Min)
          events <- events.toList
          _       = events shouldEqual List(EventStore.HighestSeqNr(SeqNr.Min))
          seqNr  <- store.save(Events.of(EventStore.Event("first", 1L), EventStore.Event("second", 2L))).flatten
          _       = seqNr shouldEqual 2L
          events <- store.events(SeqNr.Min)
          events <- events.toList
          _       = events shouldEqual List(EventStore.Event("first", 1L), EventStore.Event("second", 2L), EventStore.HighestSeqNr(2L))
          _      <- store.deleteTo(2L).flatten
          events <- store.events(SeqNr.Min)
          events <- events.toList
          _       = events shouldEqual List(EventStore.HighestSeqNr(2L))
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
          store  <- EventStoreInterop[IO, String](system, 1.second, 100, pluginId, persistenceId)
          events <- store.events(SeqNr.Min)
          error  <- events.toList.attempt
        } yield error shouldEqual FailingJournal.exception.asLeft[List[EventStore.Event[String]]]
      }

    io.unsafeRunSync()
  }

  test("journal: fail persisting events") {

    val pluginId      = "failing-journal"
    val persistenceId = EventSourcedId("#12")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          store <- EventStoreInterop[IO, String](system, 1.second, 100, pluginId, persistenceId)
          seqNr <- store.save(Events.of(EventStore.Event("first", 1L), EventStore.Event("second", 2L)))
          error <- seqNr.attempt
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
          store    <- EventStoreInterop[IO, String](system, 1.second, 100, pluginId, persistenceId)
          deleting <- store.deleteTo(SeqNr.Max)
          error    <- deleting.attempt
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
          store  <- EventStoreInterop[IO, String](system, 1.second, 100, pluginId, persistenceId)
          events <- store.events(SeqNr.Min)
          error  <- events.toList.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r)                  => fail(s"the test should fail with TimeoutException while actual result is $r")
        }
      }

    io.unsafeRunSync()
  }

  test("journal: buffer overflow on loading event") {

    val persistenceId = EventSourcedId("#17")

    val timeout  = 1.second
    val capacity = 100
    val events   = List.tabulate(capacity * 2)(n => EventStore.Event(s"event_$n", n.toLong))

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          store  <- EventStoreInterop[IO, String](system, timeout, capacity, emptyPluginId, persistenceId)
          _      <- store.save(Events.fromList(events).get).flatten
          events <- store.events(SeqNr.Min)
          _      <- IO.sleep(timeout * 2)
          error  <- events.toList.attempt
        } yield error match {
          case Left(_: EventStoreInterop.BufferOverflowException) => succeed
          case Left(e)                                            => fail(e)
          case Right(r) => fail(s"the test should fail with BufferOverflowException while actual result is $r")
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
          store <- EventStoreInterop[IO, String](system, 1.second, 100, pluginId, persistenceId)
          seqNr <- store.save(Events.of(EventStore.Event("first", 1L), EventStore.Event("second", 2L)))
          error <- seqNr.attempt
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
          store    <- EventStoreInterop[IO, String](system, 1.second, 100, pluginId, persistenceId)
          deleting <- store.deleteTo(SeqNr.Max)
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
