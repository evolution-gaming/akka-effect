package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.{AtomicWrite, PersistentRepr}
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import com.evolutiongaming.akkaeffect.{Envelope, Receive}
import com.evolutiongaming.catshelper.LogOf
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

class PersistenceFailureTest extends AnyFunSuite with Matchers {

  sealed trait Event extends FailOnEventJournal.Event
  object GoodEvent   extends Event { val fail = false }
  object FailEvent   extends Event { val fail = true  }

  val eventSourced = EventSourcedOf.const {
    IO {

      val journal  = "fail-on-event-journal"
      val snapshot = "inmemory-snapshot-store"

      EventSourced(
        eventSourcedId = EventSourcedId("test"),
        pluginIds = PluginIds(journal, snapshot),
        value = RecoveryStarted[Unit] { (_, _) =>
          Recovering
            .legacy[Unit] {
              Replay.empty[IO, Event].pure[Resource[IO, *]]
            } {
              case (_, journaller, _) =>
                Receive[Envelope[Event]] { envelope =>
                  // persist event in forked thread thus don't fail actor directly
                  journaller.append(Events.of(envelope.msg)).flatten.start.as(false)
                } {
                  IO(true)
                }.pure[Resource[IO, *]]
            }
            .pure[Resource[IO, *]]: @nowarn
        }.typeless(
          sf = _ => IO.unit,
          ef = e => IO(e.asInstanceOf[Event]),
          cf = c => IO(c.asInstanceOf[Event]),
        ).pure[Resource[IO, *]],
      )
    }
  }

  test("EventSourcedActorOf based actor must fail if journal fails to persist message") {

    implicit val log = LogOf.empty[IO]

    TestActorSystem[IO]("testing", none)
      .use { system =>
        val persistence = EventSourcedPersistence.fromAkkaPlugins[IO](system, 1.second, 100)
        def actor       = EventSourcedActorOf.actor[IO, Any, Any](eventSourced, persistence)
        val ref         = system.actorOf(Props(actor))
        def tell        = IO(ref ! GoodEvent)
        def fail        = IO(ref ! FailEvent)
        def look        = IO.fromFuture(IO(system.actorSelection(ref.path).resolveOne(2.seconds)))

        for {
          _ <- tell
          _ <- tell
          _ <- IO.sleep(1.second)
          r <- look
          _  = r shouldBe ref
          _ <- fail
          _ <- IO.sleep(1.second)
          r <- look.attempt
          _  = r shouldBe a[Left[_, _]]
        } yield {}
      }
      .unsafeRunSync()

  }

  test("PersistentActorOf based actor must fail if journal fails to persist message") {

    TestActorSystem[IO]("testing", none)
      .use { system =>
        def actor = PersistentActorOf[IO](eventSourced)
        val ref   = system.actorOf(Props(actor))
        def tell  = IO(ref ! GoodEvent)
        def fail  = IO(ref ! FailEvent)
        def look  = IO.fromFuture(IO(system.actorSelection(ref.path).resolveOne(2.seconds)))

        for {
          _ <- tell
          _ <- tell
          _ <- IO.sleep(1.second)
          r <- look
          _  = r shouldBe ref
          _ <- fail
          _ <- IO.sleep(1.second)
          r <- look.attempt
          _  = r shouldBe a[Left[_, _]]
        } yield {}
      }
      .unsafeRunSync()

  }

}

object FailOnEventJournal {
  val exception = new RuntimeException("test exception")

  trait Event {
    def fail: Boolean
  }
}

class FailOnEventJournal extends AsyncWriteJournal {

  import scala.concurrent.ExecutionContext.Implicits.global
  import FailOnEventJournal._

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
    recoveryCallback: PersistentRepr => Unit,
  ): Future[Unit] = Future.successful {}

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    Future.successful(42L)

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] =
    Future.traverse(messages.flatMap(_.payload)) { repr =>
      repr.payload match {
        case event: Event if event.fail => Future.failed(exception)
        case _                          => Future.successful(Try(()))
      }
    }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    Future.successful {}

}
