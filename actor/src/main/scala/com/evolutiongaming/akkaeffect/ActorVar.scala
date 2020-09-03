package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{BracketThrowable, FromFuture, ToFuture}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}


trait ActorVar[F[_], A] {

  def preStart(a: Resource[F, A]): Unit

  /**
    * @param f takes current state and returns tuple from next state and optional release callback
    */
  def receive(f: A => F[Option[Releasable[F, A]]]): Unit

  def postStop(): F[Unit]
}

object ActorVar {

  private val released: Throwable = new RuntimeException with NoStackTrace

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

    def stateAndFunc(a: Try[Option[State]]): (Option[State], () => Unit) = {
      a match {
        case Success(Some(a)) => (a.some     , () => ())
        case Success(None)    => (none[State], () => { stateVar = none; stop() })
        case Failure(e)       => (none[State], () => { stateVar = none; throw e })
      }
    }

    def run(fa: F[Option[State]]): Unit = {
      val future = fa
        .uncancelable
        .toFuture
      future.value match {
        case Some(result) =>
          val (state, func) = stateAndFunc(result)
          stateVar = state.map { _.asFuture }
          func()

        case None =>
          stateVar = future
            .transform { value =>
              val (state, func) = stateAndFunc(value)
              act { func() }
              state match {
                case Some(state) => state.pure[Try]
                case None        => released.raiseError[Try, State]
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
            .flatMap { case (a, release) =>
              State(a, release).some.pure[F]
            }
        }
      }

      def receive(f: A => F[Option[Releasable[F, A]]]) = {
        stateVar.foreach { state =>
          run {
            FromFuture
              .summon[F]
              .apply { state }
              .flatMap { state =>
                f(state.value)
                  .flatMap {
                    case Some(a) =>
                      val release1 = a.release match {
                        case Some(release) => release *> state.release
                        case None          => state.release
                      }
                      State(a.value, release1)
                        .some
                        .pure[F]

                    case None =>
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
            FromFuture
              .summon[F]
              .apply { state }
              .flatMap { _.release }
          case None        =>
            ().pure[F]
        }
      }
    }
  }
}
