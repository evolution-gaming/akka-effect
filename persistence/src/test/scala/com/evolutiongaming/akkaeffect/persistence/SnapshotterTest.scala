package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence.{SnapshotMetadata => _, Snapshotter => _, _}
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Deferred, IO, Sync}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorSuite, _}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class SnapshotterTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("snapshotter") {
    snapshotter[IO](actorSystem).run()
  }

  private def snapshotter[F[_]: Async: ToFuture: FromFuture](actorSystem: ActorSystem): F[Unit] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def actor(probe: Probe[F], deferred: Deferred[F, Snapshotter[F, Any]]) =
      new SnapshotterPublic { actor =>
        override def preStart() = {
          super.preStart()
          val snapshotter = Snapshotter[F, Any](actor, 1.minute)
          deferred.complete(snapshotter).toFuture
          ()
        }

        def snapshotStore = probe.actorEffect.toUnsafe

        def snapshotterId = "snapshotterId"

        def snapshotSequenceNr = 0

        def receive = PartialFunction.empty
      }

    val result = for {
      probe       <- Probe.of(actorRefOf)
      snapshotter <- Deferred[F, Snapshotter[F, Any]].toResource
      props        = Props(actor(probe, snapshotter))
      _           <- actorRefOf(props)
      snapshotter <- snapshotter.get.toResource
      result <- {
        val metadata = akka.persistence.SnapshotMetadata("snapshotterId", 0L)

        val criteria = SnapshotSelectionCriteria()

        val error = new RuntimeException with NoStackTrace

        def verify[A](
          fa: F[F[A]],
          req: Any,
          res: Any,
          expected: Either[Throwable, A]
        ) =
          for {
            a <- probe.expect[Any]
            b <- fa
            a <- a
            _  = a.msg shouldEqual req
            _ <- Sync[F].delay(a.from.tell(res, ActorRef.noSender))
            b <- b.attempt
            _  = b shouldEqual expected
          } yield {}

        def save = snapshotter.save(metadata.sequenceNr, "snapshot").map(_.void)

        val result = for {
          _ <- verify(save, SnapshotProtocolPublic.saveSnapshot(metadata, "snapshot"), SaveSnapshotSuccess(metadata), ().asRight)

          _ <- verify(save, SnapshotProtocolPublic.saveSnapshot(metadata, "snapshot"), SaveSnapshotFailure(metadata, error), error.asLeft)

          _ <- verify(snapshotter.delete(0L), SnapshotProtocolPublic.deleteSnapshot(metadata), DeleteSnapshotSuccess(metadata), ().asRight)

          _ <- verify(
            snapshotter.delete(0L),
            SnapshotProtocolPublic.deleteSnapshot(metadata),
            DeleteSnapshotFailure(metadata, error),
            error.asLeft
          )

          _ <- verify(
            snapshotter.delete(criteria),
            SnapshotProtocolPublic.deleteSnapshots("snapshotterId", criteria),
            DeleteSnapshotsSuccess(criteria),
            ().asRight
          )

          _ <- verify(
            snapshotter.delete(criteria),
            SnapshotProtocolPublic.deleteSnapshots("snapshotterId", criteria),
            DeleteSnapshotsFailure(criteria, error),
            error.asLeft
          )
        } yield {}
        result.toResource
      }
    } yield result

    result.use(_.pure[F])
  }
}
