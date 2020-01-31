package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{BracketThrowable, FromFuture, ToFuture}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}


// TODO add tests
trait ActorVar[F[_], A] {
  import ActorVar._

  def preStart(a: Resource[F, Option[A]]): Unit

  def receive(f: A => F[Release]): Unit

  def postStop(): Unit
}

object ActorVar {

  private val stopped: Throwable = new RuntimeException with NoStackTrace

  type Release = Boolean

  type Stop = () => Unit

  def apply[F[_] : BracketThrowable : ToFuture : FromFuture, A](
    act: Act,
    context: ActorContext
  ): ActorVar[F, A] = {
    val stop = () => context.stop(context.self)
    implicit val executor = context.dispatcher
    apply(act, stop)
  }

  def apply[F[_] : BracketThrowable : ToFuture : FromFuture, A](
    act: Act,
    stop: Stop)(implicit
    executor: ExecutionContext
  ): ActorVar[F, A] = {

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
          stateVar = state.map { _.pure[Future] }
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
            }
            .some
      }
    }

    new ActorVar[F, A] {

      def preStart(a: Resource[F, Option[A]]) = {
        run {
          a
            .allocated
            .flatMap { case (a, release) =>
              val release1 = release.handleError { _ => () }
              a match {
                case Some(a) => State(a, release1).some.pure[F]
                case None    => release1.as(none[State])
              }
            }
        }
      }

      def receive(f: A => F[Release]) = {
        stateVar.foreach { state =>
          run {
            FromFuture[F]
              .apply { state }
              .flatMap { state =>
                f(state.value)
                  .flatMap {
                    case false =>
                      state
                        .some
                        .pure[F]

                    case true =>
                      state.release
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

      def postStop() = {
        stateVar.foreach { state =>
          FromFuture[F]
            .apply { state }
            .flatMap { _.release }
            .toFuture
          stateVar = none
        }
      }
    }
  }
}
