package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence.{Snapshotter => _, _}
import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class SnapshotterTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("adapter") {
    implicit val toTry = ToTryFromToFuture.syncOrError[IO]
    adapter[IO](actorSystem).run()
  }

  private def adapter[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    val stopped: Throwable = new RuntimeException("stopped") with NoStackTrace

    case class Msg(snapshotter: Snapshotter[F, Any] => F[Unit])

    def actor(probe: Probe[F]) = {

      new SnapshotterPublic { actor =>

        implicit val executor = context.dispatcher

        val act = Act.adapter(self)

        val (snapshotter, release) = Snapshotter
          .adapter[F](act.value.fromFuture, actor, stopped.pure[F])
          .allocated
          .toTry
          .get

        val actorVar = ActorVar[F, Unit](act.value, context)

        override def preStart() = {
          super.preStart()
          actorVar.preStart {
            ().some.pure[Resource[F, *]]
          }
        }
        def snapshotStore = probe.actorEffect.toUnsafe

        def snapshotterId = "snapshotterId"

        def snapshotSequenceNr = 0

        def receiveMsg: Receive = {
          case Msg(f) => actorVar.receive { _ => f(snapshotter.value).as(false) }
        }

        def receive = {
          act.receive { snapshotter.receive orElse receiveMsg }
        }

        override def postStop() = {
          val result = for {
            _ <- actorVar.postStop()
            _ <- release
          } yield {}
          result.toFuture
          super.postStop()
        }
      }
    }

    trait Ask {
      def apply[A](f: Snapshotter[F, Any] => F[A]): F[A]
    }

    object Ask {
      def apply(actor: ActorEffect[F, Any, Any]): Ask = {
        new Ask {
          def apply[A](f: Snapshotter[F, Any] => F[A]) = {
            for {
              d  <- Deferred[F, F[A]]
              f1  = (snapshotter: Snapshotter[F, Any]) => for {
                a <- f(snapshotter).attempt
                _ <- d.complete(a.liftTo[F])
                _ <- a.liftTo[F]
              } yield {}
              _  <- actor.tell(Msg(f1))
              a  <- d.get.flatten
            } yield a
          }
        }
      }
    }

    val result = for {
      probe  <- Probe.of(actorRefOf)
      props   = Props(actor(probe))
      actor  <- actorRefOf(props)
      actor  <- ActorEffect.fromActor[F](actor).pure[Resource[F, *]]
      ask     = Ask(actor)
      result <- {
        val metadata = SnapshotMetadata("snapshotterId", 0L)

        val criteria = SnapshotSelectionCriteria()

        val error = new RuntimeException with NoStackTrace

        def verify[A](
          f: Snapshotter[F, Any] => F[F[A]],
          req: Any,
          res: Any,
          expected: Either[Throwable, A]
        ) = {
          for {
            a <- probe.expect
            b <- ask(f)
            a <- a
            _  = a.msg shouldEqual req
            _ <- Sync[F].delay { a.sender.tell(res, ActorRef.noSender) }
            b <- b.attempt
            _  = b shouldEqual expected
          } yield {}
        }

        def save(snapshotter: Snapshotter[F, Any]) = {
          snapshotter
            .save("snapshot")
            .map { a =>
              a.seqNr shouldEqual 0L
              a.done
            }
        }

        val result = for {
          _ <- verify(
            save,
            SnapshotProtocolPublic.saveSnapshot(metadata, "snapshot"),
            SaveSnapshotSuccess(metadata),
            ().asRight)

          _ <- verify(
            save,
            SnapshotProtocolPublic.saveSnapshot(metadata, "snapshot"),
            SaveSnapshotFailure(metadata, error),
            error.asLeft)

          _ <- verify(
            _.delete(0L),
            SnapshotProtocolPublic.deleteSnapshot(metadata),
            DeleteSnapshotSuccess(metadata),
            ().asRight)

          _ <- verify(
            _.delete(0L),
            SnapshotProtocolPublic.deleteSnapshot(metadata),
            DeleteSnapshotFailure(metadata, error),
            error.asLeft)

          _ <- verify(
            _.delete(criteria),
            SnapshotProtocolPublic.deleteSnapshots("snapshotterId", criteria),
            DeleteSnapshotsSuccess(criteria),
            ().asRight)

          _ <- verify(
            _.delete(criteria),
            SnapshotProtocolPublic.deleteSnapshots("snapshotterId", criteria),
            DeleteSnapshotsFailure(criteria, error),
            error.asLeft)
        } yield {}
        Resource.liftF(result)
      }
    } yield result

    result.use { _.pure[F] }
  }
}
