package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{BracketThrowable, FromFuture, ToFuture}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}


trait ActorVar[F[_], A] {

  def preStart(a: Resource[F, Option[A]]): Unit

  /**
    * @param f takes current state and returns tuple from next state and optional release callback
    */
  def receiveUpdate(f: A => F[Option[Releasable[F, A]]]): Unit

  /**
    * @param f takes current state and returns boolean whether to stop an actor
    */
  def receive(f: A => F[Boolean]): Unit

  def postStop(): F[Unit]
}

object ActorVar {

  private val stopped: Throwable = new RuntimeException with NoStackTrace

  type Release = Boolean

  type Stop = () => Unit

  def apply[F[_] : BracketThrowable : ToFuture : FromFuture, A](
    act: Act[Future],
    context: ActorContext
  ): ActorVar[F, A] = {
    val stop = () => context.stop(context.self)
    apply(act, stop)
  }

  def apply[F[_] : BracketThrowable : ToFuture : FromFuture, A](
    act: Act[Future],
    stop: Stop
  ): ActorVar[F, A] = {

    val executor = ParasiticExecutionContext()

    case class State(value: A, release: F[Unit])

    var stateVar = none[Future[State]]

    def stateAndFunc(a: Try[Option[State]]): (Option[State], () => Unit) = {
      a match {
        case Success(Some(a)) => (a.some, () => ())
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
          stateVar = state.map { state => Future.successful(state) }
          func()

        case None =>
          stateVar = future
            .transform { value =>
              val (state, func) = stateAndFunc(value)
              act { func() }
              state match {
                case Some(state) => state.pure[Try]
                case None        => Failure(stopped)
              }
            }(executor)
            .some
      }
    }

    new ActorVar[F, A] {

      def preStart(a: Resource[F, Option[A]]) = {
        run {
          a
            .allocated
            .flatMap { case (a, release) =>
              a match {
                case Some(a) => State(a, release).some.pure[F]
                case None    => release.as(none[State])
              }
            }
        }
      }

      def receiveUpdate(f: A => F[Option[Releasable[F, A]]]) = {
        stateVar.foreach { state =>
          run {
            FromFuture[F]
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
                      .productR {
                        error.raiseError[F, Option[State]]
                      }
                  }
              }
          }
        }
      }

      def receive(f: A => F[Boolean]): Unit = {
        receiveUpdate { a =>
          f(a).map {
            case false => Releasable(a).some
            case true  => none
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
}
