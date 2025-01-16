package akka.persistence

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.persistence.JournalProtocol.DeleteMessagesTo
import cats.effect.implicits.effectResourceOps
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, Deferred, IO, Sync}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.akkaeffect.persistence.DeleteEventsTo
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

class DeleteEventsToInteropTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("deleteEventsToInterop") {
    deleteEventsToInterop[IO](actorSystem).run()
  }

  private def deleteEventsToInterop[F[_]: Async: ToFuture: FromFuture](actorSystem: ActorSystem): F[Unit] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def actor(probe: Probe[F], deferred: Deferred[F, DeleteEventsTo[F]]) =
      new Actor { actor =>
        override def preStart() = {
          super.preStart()
          val interop = new DeleteEventsToInterop.Interop {
            def persistenceId = "persistenceId"
            def context       = actor.context
            def self          = actor.self
            def journal       = probe.actorEffect.toUnsafe
          }
          val result = for {
            deleteEventsTo <- DeleteEventsToInterop[F](interop, 1.minute)
            _              <- deferred.complete(deleteEventsTo).toResource
          } yield {}
          result.allocated.toFuture
          ()
        }

        def receive: Receive = PartialFunction.empty
      }

    val result = for {
      probe          <- Probe.of(actorRefOf)
      deleteEventsTo <- Deferred[F, DeleteEventsTo[F]].toResource
      props           = Props(actor(probe, deleteEventsTo))
      _              <- actorRefOf(props)
      deleteEventsTo <- deleteEventsTo.get.toResource
      result <- {
        val error = new RuntimeException with NoStackTrace

        def verify[A](
          fa: F[F[A]],
          req: PartialFunction[Any, Unit],
          res: Any,
          expected: Either[Throwable, A],
        ) =
          for {
            a <- probe.expect[Any]
            b <- fa
            a <- a
            _  = a.msg should matchPattern(req)
            _ <- Sync[F].delay(a.from.tell(res, ActorRef.noSender))
            b <- b.attempt
            _  = b shouldEqual expected
          } yield {}

        val result = for {
          _ <- verify(
            deleteEventsTo(0L),
            { case DeleteMessagesTo("persistenceId", 0L, _) => },
            DeleteMessagesSuccess(0L),
            ().asRight,
          )

          _ <- verify(
            deleteEventsTo(0L),
            { case DeleteMessagesTo("persistenceId", 0L, _) => },
            DeleteMessagesFailure(error, 0L),
            error.asLeft,
          )
        } yield {}
        result.toResource
      }
    } yield result

    result.use(_.pure[F])
  }
}
