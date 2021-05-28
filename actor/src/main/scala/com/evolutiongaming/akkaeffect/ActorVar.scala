package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect._
import cats.syntax.all._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.ToFuture

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}


trait ActorVar[F[_], A] {
  import ActorVar.Directive

  def preStart(resource: Resource[F, A]): Unit

  /**
    * @param f takes current state and returns tuple from next state and optional release callback
    */
  def receive(f: A => F[Directive[Releasable[F, A]]]): Unit

  def postStop(): F[Unit]
}

object ActorVar {

  type Release = Boolean

  type Stop = () => Unit


  def apply[F[_]: Async: ToFuture, A](
    act: Act[F],
    context: ActorContext
  ): ActorVar[F, A] = {
    val stop = () => context.stop(context.self)
    apply(act, context.dispatcher, stop)
  }

  def apply[F[_]: Async: ToFuture, A](
    act: Act[F],
    executor: ExecutionContext,
    stop: Stop,
  ): ActorVar[F, A] = {

    val unit = ().pure[F]

    case class State(value: A, release: F[Unit])

    def fromFuture[X](future: Future[X]) = {
      future
        .value
        .fold(
          Async[F].async[X] { f =>
            future.onComplete {
              case Success(a) => f(a.asRight)
              case Failure(a) => f(a.asLeft)
            }(executor)
          }
        ) {
          case Success(a) => a.pure[F]
          case Failure(a) => a.raiseError[F, X]
        }
    }

    var future = Future.successful(none[State])

    def serially(f: Option[State] => F[Option[State]]): Future[Option[State]] = {
      future = fromFuture(future)
        .flatMap(f)
        .toFuture
      future
    }

    def update(f: Option[State] => F[Option[State]]): Unit = {
      serially { state =>
        val result = for {
          a <- f(state)
          _ <- a match {
            case Some(_) => unit
            case None    => act { stop() }
          }
        } yield a
        result.handleErrorWith { error =>
          for {
            _ <- state.foldMapM { _.release }
            _ <- act[Any] { throw error }
            a <- error.raiseError[F, Option[State]]
          } yield a
        }
      }
        .toFuture
        .value
        .foreach { _.get }
    }

    new ActorVar[F, A] {

      def preStart(resource: Resource[F, A]) = {
        update { _ =>
          resource
            .allocated
            .flatMap { case (a, release) => State(a, release).some.pure[F] }
        }
      }

      def receive(f: A => F[Directive[Releasable[F, A]]]) = {
        update {
          case Some(state) =>
            f(state.value).flatMap {
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
          case None        =>
            none[State].pure[F]
        }
      }

      def postStop() = {
        val future = serially {
          case Some(state) =>
            state
              .release
              .as(none[State])
              .handleError { _ => none[State] }
          case None        =>
            none[State].pure[F]
        }
        fromFuture(future).void
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
