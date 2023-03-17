package com.evolutiongaming.akkaeffect.eventsourcing

import cats.effect._
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.{Events, SeqNr}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import com.evolutiongaming.retry.{Retry, Strategy}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

abstract class EngineTestCases extends AsyncFunSuite with Matchers {

  test("order of stages") {
    `order of stages`[IO].run()
  }

  test("append error prevents further appends") {
    `append error prevents further appends`[IO].run()
  }

  test("client errors do not have global impact") {
    `client errors do not have global impact`[IO].run()
  }

  test("after stop engine finishes with inflight elements and releases") {
    `after stop engine finishes with inflight elements and releases`[IO].run()
  }

  test("release finishes with inflight elements") {
    `release finishes with inflight elements`[IO].run()
  }

  test("effective state should not change after persistence failed") {
    `release finishes with inflight elements`[IO].run()
  }

  def engine[F[_]: Async: ToFuture: FromFuture, S, E](
    initial: Engine.State[S],
    append: Engine.Append[F, E]
  ): Resource[F, Engine[F, S, E]]

  def `order of stages`[F[_]: Async: ToFuture: FromFuture]: F[Unit] = {

    type E = SeqNr

    final case class S(events: List[E])

    sealed trait Action

    object Action {
      final case class Load(name: String) extends Action
      final case class Validate(name: String, seqNr: SeqNr) extends Action
      final case class Append(events: Events[E]) extends Action
      final case class Effect(name: String, seqNr: Either[Throwable, SeqNr])
          extends Action
    }

    trait Actions {

      def add(a: Action): F[Unit]

      def get: F[List[Action]]
    }

    object Actions {

      def apply(): F[Actions] = {
        Ref[F]
          .of(List.empty[Action])
          .map { ref =>
            new Actions {

              def add(a: Action) = ref.update { a :: _ }

              def get = ref.get.map { _.reverse }
            }
          }
      }
    }

    def appendOf(actions: Actions): F[Engine.Append[F, E]] = {
      Engine.Append
        .of[F, E](SeqNr.Min)
        .map { append => events =>
          {
            for {
              _ <- actions.add(Action.Append(events))
              seqNr <- append(events)
            } yield seqNr
          }
        }
    }

    val initial = Engine.State(S(List.empty), 0L)

    val result = for {
      actions <- Actions().toResource
      append <- appendOf(actions).toResource
      engine <- engine(initial, append)
      result = {
        def load(name: String,
                 delay: F[F[F[F[Unit]]]]): F[Validate[F, S, E, Unit]] = {
          for {
            delay <- delay
            _ <- actions.add(Action.Load(name))
            delay <- delay
          } yield {
            Validate[S] { (state, seqNr) =>
              for {
                delay <- delay
                _ <- actions.add(Action.Validate(name, seqNr))
              } yield {
                val state1 = state.copy(events = seqNr :: state.events)
                val effect = Effect { seqNr =>
                  for {
                    _ <- delay
                    _ <- actions.add(Action.Effect(name, seqNr))
                  } yield {}
                }
                val change = Change(state1, Events.of(seqNr))
                Directive(change, effect)
              }
            }.convert[S, E, Unit](_.pure[F], _.pure[F], _.pure[F], _.pure[F])
          }
        }

        val retry =
          Retry[F, Throwable](Strategy.fibonacci(10.millis).limit(500.millis))

        for {
          dr0 <- Deferred[F, Unit]
          dv0 <- Deferred[F, Unit]
          de0 <- Deferred[F, Unit]
          a0 <- engine(
            load("a", dr0.get.as(().pure[F].as(dv0.get.as(de0.get))))
          )

          dr1a <- Deferred[F, Unit]
          dr1b <- Deferred[F, Unit]
          dv1 <- Deferred[F, Unit]
          de1a <- Deferred[F, Unit]
          de1b <- Deferred[F, Unit]
          de1 = de1a.complete(()) *> de1b.get
          _ <- engine(
            load(
              "b",
              dr1a.get.as(dr1b.complete(()).as(dv1.complete(()).as(de1)))
            )
          )

          dr2a <- Deferred[F, Unit]
          dr2b <- Deferred[F, Unit]
          dv2 <- Deferred[F, Unit]
          de2 <- Deferred[F, Unit]
          a2 <- engine(
            load("c", dr2a.get.as(dr2b.complete(()).as(dv2.get.as(de2.get))))
          )

          _ <- dr2a.complete(())
          _ <- dr2b.get

          as <- actions.get
          _ = as shouldEqual List(Action.Load("c"))

          _ <- dr1a.complete(())
          _ <- dr1b.get
          as <- actions.get
          _ = as shouldEqual List(Action.Load("c"), Action.Load("b"))
          _ <- dr0.complete(())
          _ <- dv0.complete(())
          _ <- dv1.get
          _ <- de0.complete(())
          _ <- a0

          _ <- de1a.get
          _ <- dv2.complete(())
          _ <- retry {
            for {
              as <- actions.get
              _ <- Sync[F].delay {
                as.lastOption shouldEqual Action.Append(Events.of(2L)).some
              }
            } yield {}
          }

          _ <- de2.complete(())
          _ <- de1b.complete(())
          _ <- a2

          as <- actions.get
          _ = as.collect { case a: Action.Load => a } shouldEqual List(
            Action.Load("c"),
            Action.Load("b"),
            Action.Load("a")
          )

          _ = as.collect { case a: Action.Validate => a } shouldEqual List(
            Action.Validate("a", 0L),
            Action.Validate("b", 1L),
            Action.Validate("c", 2L)
          )

          _ = as.collect { case a: Action.Effect => a } shouldEqual List(
            Action.Effect("a", 1L.asRight),
            Action.Effect("b", 2L.asRight),
            Action.Effect("c", 3L.asRight)
          )

          _ = as.collect { case a: Action.Append => a } shouldEqual List(
            Action.Append(Events.of(0L)),
            Action.Append(Events.of(1L)),
            Action.Append(Events.of(2L))
          )
        } yield {}
      }
      _ <- result.toResource
    } yield {}

    result.use { _.pure[F] }
  }

  def `append error prevents further appends`[F[_]: Async: ToFuture: FromFuture]
    : F[Unit] = {

    type S = Unit
    type E = Unit

    val error: Throwable = new RuntimeException with NoStackTrace

    val initial = Engine.State((), SeqNr.Min)
    val result = for {
      seqNrRef <- Ref[F].of(SeqNr.Min).toResource
      append = new Engine.Append[F, E] {
        def apply(events: Events[E]) = {
          val size = events.size
          for {
            seqNr <- seqNrRef.modify { seqNr =>
              val seqNr1 = seqNr + size
              (seqNr1, seqNr1)
            }
            _ <- error.raiseError[F, Unit].whenA(seqNr == 2)
          } yield seqNr
        }
      }
      engine <- engine(initial, append)
      result = {
        def load = {
          Validate
            .const {
              val effect = Effect { _.pure[F] }
              val change = Change((), Events.of(()))
              Directive(change, effect).pure[F]
            }
            .pure[F]
        }

        for {
          a0 <- engine(load)
          a1 <- engine(load)
          a2 <- engine(load)
          a <- a0
          _ = a shouldEqual 1.asRight
          a <- a1
          _ = a shouldEqual error.asLeft
          a <- a2
          _ = a shouldEqual error.asLeft
          seqNr <- seqNrRef.get
          _ = seqNr shouldEqual 2L
        } yield {}
      }
      _ <- result.toResource
    } yield {}

    result.use { _.pure[F] }
  }

  def `client errors do not have global impact`[F[_]: Async: ToFuture: FromFuture]
    : F[Unit] = {

    type S = Unit
    type E = Unit

    val error: Throwable = new RuntimeException with NoStackTrace

    val initial = Engine.State((), SeqNr.Min)
    val result = for {
      append <- Engine.Append
        .const[F, E](error.raiseError[F, SeqNr])
        .pure[Resource[F, *]]
      engine <- engine(initial, append)
      result = {
        for {
          _ <- ().pure[F]

          success = Validate.effect[S, E] { _.liftTo[F].as(0) }
          result <- engine(success.pure[F]).flatten
          _ = result shouldEqual 0

          load = error.raiseError[F, Validate[F, S, E, Unit]]
          result <- engine(load)
          result <- result.attempt
          _ = result shouldEqual error.asLeft

          success = Validate.effect[S, E] { _.liftTo[F].as(1) }
          result <- engine(success.pure[F]).flatten
          _ = result shouldEqual 1

          validate = Validate.const(
            error.raiseError[F, Directive[F, S, E, Unit]]
          )
          result <- engine(validate.pure[F])
          result <- result.attempt
          _ = result shouldEqual error.asLeft

          success = Validate.effect[S, E] { _.liftTo[F].as(2) }
          result <- engine(success.pure[F]).flatten
          _ = result shouldEqual 2

          effect = Validate.effect[S, E] { _ =>
            error.raiseError[F, Unit]
          }
          result <- engine(effect.pure[F])
          result <- result.attempt
          _ = result shouldEqual error.asLeft

          success = Validate.effect[S, E] { _.liftTo[F].as(3) }
          result <- engine(success.pure[F]).flatten
          _ = result shouldEqual 3

          effect = Effect[F, Either[Throwable, SeqNr]] { _.pure[F] }
          append = Validate.const(
            Directive(Change((), Events.of(())), effect).pure[F]
          )
          result <- engine(append.pure[F]).flatten
          _ = result shouldEqual error.asLeft

          success = Validate.effect[S, E] { _.liftTo[F] }
          result <- engine(success.pure[F])
          result <- result.attempt
          _ = result shouldEqual error.asLeft
        } yield {}
      }
      _ <- result.toResource
    } yield {}

    result.use { _.pure[F] }
  }

  def `after stop engine finishes with inflight elements and releases`[F[_]: Async: ToFuture: FromFuture]
    : F[Unit] = {

    type S = Unit
    type E = Unit

    val initial = Engine.State((), SeqNr.Min)
    val result = for {
      append <- Engine.Append.of[F, E](initial.seqNr).toResource
      engine <- engine(initial, append)
      result = {
        for {
          d0a <- Deferred[F, Unit]
          d0b <- Deferred[F, Either[Throwable, SeqNr]]
          l0 = Validate
            .effect[S, E] { seqNr =>
              d0b.complete(seqNr) *> d0a.get
            }
            .pure[F]
          _ <- engine(l0)

          d1 <- Deferred[F, Unit]
          l1 = Validate
            .const(
              d1.get
                .as(Directive.stop[F, S, E, Either[Throwable, SeqNr]](Effect {
                  _.pure[F]
                }))
            )
            .pure[F]
          a1 <- engine(l1)

          d2 <- Deferred[F, Unit]
          l2 = d2
            .complete(())
            .as(Validate.effect[S, E] { _.pure[F] })
          a2 <- engine(l2)

          _ <- d2.get
          seqNr <- d0b.get
          _ = seqNr shouldEqual 0L.asRight
          _ <- d1.complete(())
          _ <- d0a.complete(())
          seqNr <- a1
          _ = seqNr shouldEqual 0L.asRight

          a <- a2
          _ = a shouldEqual Engine.stopped.asLeft
        } yield {}
      }
      _ <- result.toResource
    } yield {}

    result.use { _.pure[F] }
  }

  def `effective state should not change after persistence failed`[F[_]: Async: ToFuture: FromFuture]
    : F[Unit] = {
    val error = new RuntimeException with NoStackTrace

    type E = Unit

    val directive = Directive.change({}, Events.of({})) {
      _.liftTo[F].void
    }
    val load = Validate.const {
      directive.pure[F]
    }

    val initial = Engine.State((), SeqNr.Min)

    for {
      appendRef <- Ref.of[F, Either[Throwable, SeqNr]](initial.seqNr.asRight)
      append = new Engine.Append[F, E] {
        override def apply(events: Events[E]): F[SeqNr] =
          for {
            v <- appendRef.get
            n <- v.liftTo[F]
          } yield n
      }

      _ <- engine(initial, append).use { engine =>
        for {
          _ <- engine(load.pure[F]).flatten.attempt
          eff0 <- engine.effective
          opt0 <- engine.optimistic
          _ = eff0 shouldEqual opt0

          _ <- appendRef.set(error.asLeft)
          _ <- engine(load.pure[F]).flatten.attempt
          eff1 <- engine.effective
          opt1 <- engine.optimistic
          _ = eff1.seqNr shouldEqual 1
          _ = opt1.seqNr shouldEqual 2
        } yield {}
      }
    } yield {}
  }

  def `release finishes with inflight elements`[F[_]: Async: ToFuture: FromFuture]
    : F[Unit] = {

    val error: Throwable = new RuntimeException with NoStackTrace

    type S = Unit
    type E = Unit

    val initial = Engine.State((), SeqNr.Min)
    for {
      append <- Engine.Append.of[F, E](initial.seqNr)
      ab <- engine(initial, append).allocated
      (engine, release) = ab

      d0a <- Deferred[F, Unit]
      d0b <- Deferred[F, Unit]
      d0 = d0a.complete(()) *> d0b.get
      l0 = Validate
        .effect[S, E] { _ =>
          d0
        }
        .pure[F]
      a0 <- engine(l0)

      d1a <- Deferred[F, Unit]
      d1b <- Deferred[F, Unit]
      d1 = d1a.complete(()) *> d1b.get
      l1 = Validate.const(d1.as(Directive.effect[S, E] { _.pure[F] })).pure[F]
      a1 <- engine(l1)

      d2a <- Deferred[F, Unit]
      d2b <- Deferred[F, Unit]
      d2 = d2a.complete(()) *> d2b.get
      l2 = d2.as(Validate.effect[S, E] { _.pure[F] })
      a2 <- engine(l2)

      _ <- d0a.get
      _ <- d1a.get
      _ <- d2a.get

      _ <- release

      a3 <- engine(error.raiseError[F, Validate[F, S, E, Unit]]).attempt
      _ = a3 shouldEqual Engine.released.asLeft

      _ <- d0b.complete(())
      _ <- d1b.complete(())
      _ <- d2b.complete(())

      _ <- a0
      a <- a1
      _ = a shouldEqual 0L.asRight
      a <- a2
      _ = a shouldEqual 0L.asRight
    } yield {}
  }
}
