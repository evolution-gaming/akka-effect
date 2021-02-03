package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect._
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{BracketThrowable, FromFuture, ToFuture}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}


trait ActorVar[F[_], A] {
  import ActorVar.Directive

  def preStart(a: Resource[F, A]): Unit

  /**
    * @param f takes current state and returns tuple from next state and optional release callback
    */
  def receive(f: A => F[Directive[Releasable[F, A]]]): Unit

  def postStop(): F[Unit]
}

object ActorVar {

  type Release = Boolean

  type Stop = () => Unit


  def apply[F[_]: BracketThrowable: ToFuture: FromFuture, A](
    act: Act[Future],
    context: ActorContext
  ): ActorVar[F, A] = {
    val stop = () => context.stop(context.self)
    apply(act, stop)
  }

  def apply[F[_]: BracketThrowable: ToFuture: FromFuture, A](
    act: Act[Future],
    stop: Stop
  ): ActorVar[F, A] = {

    val executor = ParasiticExecutionContext()

    case class State(value: A, release: F[Unit])

    var stateVar = none[Future[State]]

    def stateAndFunc(a: Try[Option[State]]): (Option[State], Option[() => Unit]) = {
      a match {
        case Success(Some(a)) => (a.some, none)
        case Success(None)    => (none[State], (() => { stateVar = none; stop() }).some)
        case Failure(e)       => (none[State], (() => { stateVar = none; throw e }).some)
      }
    }

    def run(fa: F[Option[State]]): Unit = {
      val future = fa.toFuture
      future.value match {
        case Some(result) =>
          val (state, func) = stateAndFunc(result)
          stateVar = state.map { _.asFuture }
          func.foreach { _.apply() }

        case None =>
          stateVar = future
            .transform { value =>
              val (state, func) = stateAndFunc(value)
              func.foreach { func => act { func() } }
              state match {
                case Some(state) => state.pure[Try]
                case None        => new Released().raiseError[Try, State]
              }
            }(executor)
            .some
      }
    }

    new ActorVar[F, A] {

      def preStart(a: Resource[F, A]) = {
        run {
          a
            .allocated
            .flatMap { case (a, release) => State(a, release).some.pure[F] }
        }
      }

      def receive(f: A => F[Directive[Releasable[F, A]]]) = {
        stateVar.foreach { state =>
          run {
            FromFuture[F]
              .apply { state }
              .flatMap { state =>
                f(state.value)
                  .flatMap {
                    case Directive.Update(a) =>
                      val release1 = a.release match {
                        case Some(release) => release *> state.release
                        case None          => state.release
                      }
                      State(a.value, release1)
                        .some
                        .pure[F]

                    case Directive.Ignore =>
                      state
                        .some
                        .pure[F]

                    case Directive.Stop =>
                      state
                        .release
                        .handleError { _ => () }
                        .as(none[State])
                  }
                  .handleErrorWith { error =>
                    state
                      .release
                      .productR { error.raiseError[F, Option[State]] }
                  }
              }
          }
        }
      }

      def postStop() = {
        stateVar match {
          case Some(state) =>
            stateVar = none
            FromFuture[F]
              .apply { state }
              .flatMap { _.release }
          case None        =>
            ().pure[F]
        }
      }
    }
  }


  sealed trait Directive[+A]

  object Directive {

    def update[A](value: A): Directive[A] = Update(value)

    def ignore[A]: Directive[A] = Ignore

    def stop[A]: Directive[A] = Stop


    final case class Update[A](value: A) extends Directive[A]

    final case object Ignore extends Directive[Nothing]

    final case object Stop extends Directive[Nothing]


    implicit class DirectiveOps[A](val self: Directive[A]) extends AnyVal {

      def map[B](f: A => B): Directive[B] = self match {
        case Update(a) => Update(f(a))
        case Ignore    => Ignore
        case Stop      => Stop
      }
    }
  }


  final class Released extends RuntimeException with NoStackTrace
}
