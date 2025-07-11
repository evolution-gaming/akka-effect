package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect.*
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.util.Serially
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.ToFuture

import scala.util.control.NoStackTrace

private[akkaeffect] trait ActorVar[F[_], A] {
  import ActorVar.Directive

  def preStart(resource: Resource[F, A]): Unit

  /** @param f
    *   takes current state and returns tuple from next state and optional release callback
    */
  def receive(f: A => F[Directive[Releasable[F, A]]]): Unit

  def postStop(): F[Unit]
}

private[akkaeffect] object ActorVar {

  type Release = Boolean

  type Stop = () => Unit

  def apply[F[_]: Async: ToFuture, A](
    act: Act[F],
    context: ActorContext,
  ): ActorVar[F, A] = {
    val stop = () => context.stop(context.self)
    apply(act, stop)
  }

  def apply[F[_]: Async: ToFuture, A](
    act: Act[F],
    stop: Stop,
  ): ActorVar[F, A] = {

    val unit = ().pure[F]

    case class State(value: A, release: F[Unit])

    val serially = Serially[F, Option[State]](none)

    def update(f: Option[State] => F[Option[State]]): Unit = {
      serially.apply { state =>
        val result = for {
          a <- f(state)
          _ <- a match {
            case Some(_) => unit
            case None    => act(stop())
          }
        } yield a
        result.handleErrorWith { error =>
          for {
            _ <- state.foldMapM(_.release)
            _ <- act[Any](throw error)
            a <- error.raiseError[F, Option[State]]
          } yield a
        }
      }.toFuture
      ()
    }

    new ActorVar[F, A] {

      def preStart(resource: Resource[F, A]): Unit =
        update { _ =>
          resource.allocated.flatMap { case (a, release) => State(a, release).some.pure[F] }
        }

      def receive(f: A => F[Directive[Releasable[F, A]]]): Unit =
        update {
          case Some(state) =>
            f(state.value).flatMap {
              case Directive.Update(a) =>
                val release1 = a.release match {
                  case Some(release) => release *> state.release
                  case None          => state.release
                }
                State(a.value, release1).some.pure[F]

              case Directive.Ignore =>
                state.some.pure[F]

              case Directive.Stop =>
                state.release.handleError(_ => ()).as(none[State])
            }
          case None =>
            none[State].pure[F]
        }

      def postStop(): F[Unit] =
        serially {
          case Some(state) =>
            state.release.as(none[State]).handleError(_ => none[State])
          case None =>
            none[State].pure[F]
        }
    }
  }

  sealed trait Directive[+A]

  object Directive {

    def update[A](value: A): Directive[A] = Update(value)

    def ignore[A]: Directive[A] = Ignore

    def stop[A]: Directive[A] = Stop

    final case class Update[A](value: A) extends Directive[A]

    case object Ignore extends Directive[Nothing]

    case object Stop extends Directive[Nothing]

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
