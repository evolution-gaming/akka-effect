package com.evolutiongaming.akkaeffect.util

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._

import java.util.concurrent.atomic.AtomicReference

/** Provides serial access to an internal state.
  *
  * The class differs from [[cats.effect.concurrent.Ref]] by the ability to execute
  * an effect and a guarantee that the operations will be executed in the same
  * order these arrived given these were called from the same thread.
  */
private[akkaeffect] trait Serially[F[_], A] {
  def apply(f: A => F[A]): F[Unit]
}

private[akkaeffect] object Serially {

  def apply[F[_]: Concurrent, A](value: A): Serially[F, A] = {

    type Task = A => F[A]

    sealed abstract class S

    object S {
      final case class Idle(value: A) extends S
      final case class Active(task: Task) extends S
      final case object Active extends S
    }

    val ref = new AtomicReference[S](S.Idle(value))

    val unit = ().asRight[(A, Task)]

    def start(a: A, task: Task) = {
      (a, task).tailRecM { case (a, task) =>
        for {
          a <- task(a)
          s <- Sync[F].delay {
            ref.getAndUpdate {
              case _: S.Active => S.Active
              case S.Active    => S.Idle(a)
              case _: S.Idle   => S.Idle(a)
            }
          }
        } yield s match {
          case s: S.Active => (a, s.task).asLeft[Unit]
          case S.Active    => unit
          case _: S.Idle   => unit
        }
      }
    }

    class Main
    new Main with Serially[F, A] {
      def apply(f: A => F[A]) = {
        for {
          d <- Deferred[F, Either[Throwable, Unit]]
          t = (a: A) => {
            for {
              b <- f(a).attempt
              _ <- d.complete(b.void)
            } yield {
              b.getOrElse(a)
            }
          }
          s <- Sync[F].delay {
            ref.getAndUpdate {
              case _: S.Idle => S.Active
              case s: S.Active =>
                val task = (a: A) =>
                  Sync[F].defer {
                    for {
                      a <- s.task(a)
                      a <- t(a)
                    } yield a
                  }
                S.Active(task)
              case S.Active => S.Active(t)
            }
          }
          _ <- s match {
            case s: S.Idle   => start(s.value, t)
            case _: S.Active => Concurrent[F].unit
            case S.Active    => Concurrent[F].unit
          }
          a <- d.get
          a <- a.liftTo[F]
        } yield a
      }
    }
  }
}
