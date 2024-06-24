package akka.persistence

import akka.persistence.journal.AsyncWriteJournal
import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.persistence.{EventSourcedId, EventStore, Events, SeqNr}
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import com.evolutiongaming.akkaeffect.util.AtomicRef
import com.evolutiongaming.catshelper.LogOf
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeoutException
import javax.naming.OperationNotSupportedException
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

class EventStoreInteropTest extends AnyFunSuite with Matchers {

  implicit val log: LogOf[IO] = LogOf.empty[IO]

  val emptyPluginId = ""

  test("journal: replay (nothing), save, replay, delete, replay") {

    val persistenceId = EventSourcedId("#10")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          store  <- EventStoreInterop[IO](Persistence(system), 1.second, 100, emptyPluginId, persistenceId)
          events <- store.events(SeqNr.Min)
          events <- events.toList
          _       = events shouldEqual List(EventStore.HighestSeqNr(SeqNr.Min))
          seqNr  <- store.save(Events.of(EventStore.Event("first", 1L), EventStore.Event("second", 2L))).flatten
          _       = seqNr shouldEqual 2L
          events <- store.events(SeqNr.Min)
          events <- events.toList
          _ = events shouldEqual List(
            EventStore.Event("first", 1L),
            EventStore.Event("second", 2L),
            EventStore.HighestSeqNr(2L),
          )
          _      <- store.deleteTo(2L).flatten
          events <- store.events(SeqNr.Min)
          events <- events.toList
          _       = events shouldEqual List(EventStore.HighestSeqNr(2L))
        } yield {}
      }

    io.unsafeRunSync()
  }

  test("journal: start processing events before final event received") {

    val pluginId      = "delayed-journal"
    val persistenceId = EventSourcedId("#21")

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        val n        = 1000L
        val maxSeqNr = n - 1 // SeqNr starts from 0

        def events: List[EventStore.Event[Any]] =
          List.range(0, n).map(n => EventStore.Event(s"event_$n", n.toLong))

        for {
          // persist n events
          store <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
          seqNr <- store.save(Events.fromList(events).get).flatten
          _      = seqNr shouldEqual maxSeqNr

          // recover events if persistence delayed for any event
          stream <- store.events(SeqNr.Min)
          error  <- stream.toList.attempt
          _       = error shouldBe a[Left[TimeoutException, _]]

          // recover events if persistence delayed after recovering half of events
          half    = n / 2
          permit <- DelayedPersistence.permit(half.toInt - 10)
          stream <- store.events(SeqNr.Min)
          done   <- IO.deferred[Unit]
          test = stream.foldWhileM(half) {
            case (1L, _) => done.complete {}.as(().asRight[Long])
            case (n, _)  => (n - 1).asLeft[Unit].pure[IO]
          }
          _ <- test.start
          _ <- permit.inc(10)
          // the timeout used only to fail the test if events cannot be consumed
          // its value should not corelate with `EventStoreInterop` timeout
          _ <- done.get.timeout(500.millis)

          // recover events if persistence does not delayed
          _      <- DelayedPersistence.permit(n.toInt)
          stream <- store.events(SeqNr.Min)
          events <- stream.toList
          _       = events.length shouldBe n + 1 // + HighestSeqNr event
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
          store  <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
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
          store <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
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
          store    <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
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
          store  <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
          events <- store.events(SeqNr.Min)
          error  <- events.toList.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r) => fail(s"the test should fail with TimeoutException while actual result is $r")
        }
      }

    io.unsafeRunSync()
  }

  test("journal: buffer overflow on loading event") {

    val persistenceId = EventSourcedId("#17")

    val timeout  = 1.second
    val capacity = 100
    val events   = List.tabulate(capacity * 2)(n => EventStore.Event[Any](s"event_$n", n.toLong))

    val io = TestActorSystem[IO]("testing", none)
      .use { system =>
        for {
          store  <- EventStoreInterop[IO](Persistence(system), timeout, capacity, emptyPluginId, persistenceId)
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
          store <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
          seqNr <- store.save(Events.of(EventStore.Event("first", 1L), EventStore.Event("second", 2L)))
          error <- seqNr.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r) => fail(s"the test should fail with TimeoutException while actual result is $r")
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
          store    <- EventStoreInterop[IO](Persistence(system), 1.second, 100, pluginId, persistenceId)
          deleting <- store.deleteTo(SeqNr.Max)
          error    <- deleting.attempt
        } yield error match {
          case Left(_: TimeoutException) => succeed
          case Left(e)                   => fail(e)
          case Right(r) => fail(s"the test should fail with TimeoutException while actual result is $r")
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
    recoveryCallback: PersistentRepr => Unit,
  ): Future[Unit] = Future.failed(FailingJournal.exception)

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future.failed(FailingJournal.exception)

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] =
    Future.failed(FailingJournal.exception)

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    Future.failed(FailingJournal.exception)

}

class InfiniteJournal extends AsyncWriteJournal {

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit,
  ): Future[Unit] = Future.never

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = Future.never

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = Future.never

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = Future.never

}

object DelayedPersistence {

  trait Permit {
    def get: IO[Unit]
    def inc(i: Int = 1): IO[Unit]
  }
  object Permit {

    sealed trait Type
    object Issued                                  extends Type
    case class Awaiting(await: Deferred[IO, Unit]) extends Type

    val never = unsafe(0)

    def unsafe(n: Int): Permit = new Permit {

      private val permits = IO.ref(List.fill[Type](n)(Issued)).unsafeRunSync()

      def get: IO[Unit] =
        permits.flatModify {

          case Nil =>
            val await = IO.deferred[Unit].unsafeRunSync()
            List(Awaiting(await)) -> await.get

          case Issued :: t =>
            t -> IO.unit

          case Awaiting(await) :: t =>
            t -> await.get

        }

      def inc(i: Int): IO[Unit] =
        permits.flatModify {
          case Nil                  => List.fill(i)(Issued)           -> IO.unit
          case issued @ Issued :: _ => issued ++ List.fill(i)(Issued) -> IO.unit
          case Awaiting(await) :: t => t -> await.complete {} *> { if (i > 1) inc(i - 1) else IO.unit }
        }

    }
  }

  private val state = IO.ref(Permit.never).unsafeRunSync()

  def permit(n: Int = 1): IO[Permit] = {
    val p = Permit.unsafe(n)
    state.set(p).as(p)
  }

  def permit: IO[Permit] = state.getAndSet(Permit.never)

}

class DelayedPersistence extends AsyncWriteJournal {

  import DelayedPersistence.*
  import scala.concurrent.ExecutionContext.Implicits.{global => ec}

  private val state = AtomicRef[Map[String, Vector[PersistentRepr]]](Map.empty)

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit,
  ): Future[Unit] = {
    val events =
      IO.delay {
        state
          .get()
          .getOrElse(persistenceId, Vector.empty)
          .filter { event =>
            event.sequenceNr >= fromSequenceNr && event.sequenceNr <= toSequenceNr
          }
      }

    val io = for {
      permit <- permit
      events <- events
      _ <- events.traverse { event =>
        for {
          _ <- permit.get
          _ <- IO.delay(recoveryCallback(event))
        } yield ()
      }
    } yield {}

    io.void.unsafeToFuture()
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future {
      state
        .get()
        .getOrElse(persistenceId, Vector.empty)
        .foldLeft(fromSequenceNr) { (max, event) =>
          if (event.sequenceNr > max) event.sequenceNr else max
        }
    }

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] =
    Future {
      messages.foreach { atomicWrite =>
        val events        = atomicWrite.payload
        val persistenceId = atomicWrite.persistenceId
        state.update { state =>
          val current = state.getOrElse(persistenceId, Vector.empty)
          state.updated(persistenceId, current ++ events)
        }
      }
      Seq.empty[Try[Unit]]
    }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    Future.failed(new OperationNotSupportedException("deleteMessagesTo is not supported in the test"))

}
