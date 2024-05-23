package com.evolutiongaming.akkaeffect

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.kernel.Deferred
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.ActorVar.Directive
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.{ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class ActorVarTest extends AsyncFunSuite with Matchers {

  test("ActorVar") {
    actorVar[IO].run()
  }

  def actorVar[F[_]: Async: ToFuture: ToTry]: F[Unit] = {

    sealed trait Action

    object Action {
      final case object Allocated                                  extends Action
      final case object Released                                   extends Action
      final case class Updated(before: Int, after: Directive[Int]) extends Action
      final case class Released(state: Int)                        extends Action
    }

    trait Actions {

      def add(a: Action): F[Unit]

      def get: F[List[Action]]
    }

    object Actions {

      def apply(): F[Actions] =
        Ref[F]
          .of(List.empty[Action])
          .map { ref =>
            new Actions {

              def add(a: Action) = ref.update(a :: _)

              def get = ref.get.map(_.reverse)
            }
          }
    }

    def actorVar(stop: F[Unit]) = Sync[F].delay {
      val act = new Act[F] {

        def postStop(): Unit = {}

        def apply[A](f: => A) = Sync[F].delay(f)
      }
      ActorVar[F, Int](act, () => stop.toTry.get)
    }

    for {
      stopped  <- Deferred[F, Unit]
      actorVar <- actorVar(stopped.complete(()).void)
      deferred <- Deferred[F, Unit]
      actions  <- Actions()
      resource = Resource.make {
        for {
          _ <- deferred.get
          _ <- actions.add(Action.Allocated)
        } yield 0
      } { _ =>
        actions.add(Action.Released)
      }
      _ <- Sync[F].delay(actorVar.preStart(resource))
      set = (state: Directive[Int]) =>
        Sync[F].delay {
          actorVar.receive { state0 =>
            for {
              _ <- actions.add(Action.Updated(state0, state))
            } yield for {
              state <- state
            } yield {
              val release = actions.add(Action.Released(state))
              Releasable(state, release.some)
            }
          }
        }
      _       <- GenSpawn[F].cede
      _       <- set(Directive.update(1))
      _       <- GenSpawn[F].cede
      _       <- set(Directive.update(2))
      _       <- GenSpawn[F].cede
      _       <- set(Directive.ignore)
      _       <- GenSpawn[F].cede
      _       <- set(Directive.stop)
      _       <- GenSpawn[F].cede
      _       <- set(Directive.update(3))
      _       <- actorVar.postStop().start
      _       <- deferred.complete(())
      _       <- stopped.get
      actions <- actions.get
      _ = actions shouldEqual List(
        Action.Allocated,
        Action.Updated(0, Directive.update(1)),
        Action.Updated(1, Directive.update(2)),
        Action.Updated(2, Directive.ignore),
        Action.Updated(2, Directive.stop),
        Action.Released(2),
        Action.Released(1),
        Action.Released,
      )
    } yield {}
  }
}
