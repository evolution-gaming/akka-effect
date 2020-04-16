package akka.persistence

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.persistence.JournalProtocol.DeleteMessagesTo
import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.DeleteEventsTo
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class DeleteEventsToInteropTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("deleteEventsToInterop") {
    implicit val toTry = ToTryFromToFuture.syncOrError[IO]
    deleteEventsToInterop[IO](actorSystem).run()
  }

  private def deleteEventsToInterop[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def actor(probe: Probe[F], deferred: Deferred[F, DeleteEventsTo[F]]) = {

      new Actor { actor =>

        override def preStart() = {
          super.preStart()
          val interop = new DeleteEventsToInterop.Interop {
            def persistenceId = "persistenceId"
            def context = actor.context
            def self = actor.self
            def journal = probe.actorEffect.toUnsafe
          }
          val result = for {
            deleteEventsTo <- DeleteEventsToInterop[F](interop, 1.minute)
            _              <- deferred.complete(deleteEventsTo).toResource
          } yield {
          }
          result.allocated.toFuture
          ()
        }

        def receive = PartialFunction.empty
      }
    }

    val result = for {
      probe          <- Probe.of(actorRefOf)
      deleteEventsTo <- Deferred[F, DeleteEventsTo[F]].toResource
      props           = Props(actor(probe, deleteEventsTo))
      _              <- actorRefOf(props)
      deleteEventsTo <- deleteEventsTo.get.toResource
      result         <- {
        val error = new RuntimeException with NoStackTrace

        def verify[A](
          fa: F[F[A]],
          req: PartialFunction[Any, Unit],
          res: Any,
          expected: Either[Throwable, A]
        ) = {
          for {
            a      <- probe.expect
            b      <- fa
            a      <- a
            sender  = a.msg should matchPattern(req)
            _      <- Sync[F].delay { a.from.tell(res, ActorRef.noSender) }
            b      <- b.attempt
            _       = b shouldEqual expected
          } yield {}
        }

        val result = for {
          _ <- verify(
            deleteEventsTo(0L),
            { case DeleteMessagesTo("persistenceId", 0L, _) => },
            DeleteMessagesSuccess(0L),
            ().asRight)

          _ <- verify(
            deleteEventsTo(0L),
            { case DeleteMessagesTo("persistenceId", 0L, _) => },
            DeleteMessagesFailure(error, 0L),
            error.asLeft)
        } yield {}
        result.toResource
      }
    } yield result

    result.use { _.pure[F] }
  }
}

