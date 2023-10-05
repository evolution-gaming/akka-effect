package com.evolutiongaming.akkaeffect.util

import cats.effect.{Async, Concurrent}
import cats.effect.kernel.Ref
import cats.syntax.all._

private[akkaeffect] trait Serially[F[_], A] {
  def apply(f: A => F[A]): F[Unit]
}

private[akkaeffect] object Serially {

  def apply[F[_]: Async, A](value: A): Serially[F, A] = {

    type Task = A => F[A]

    sealed abstract class S

    object S {
      final case class Idle(value: A) extends S
      final case class Active(task: Task) extends S
      final case object Active extends S
    }

    val ref = Ref.unsafe[F, S](S.Idle(value))

    val unit = ().asRight[(A, Task)]

    def start(a: A, task: Task) = {
      (a, task).tailRecM { case (a, task) =>
        for {
          a <- task(a)
          a <- ref.modify {
            case s: S.Active => (S.Active, (a, s.task).asLeft[Unit])
            case S.Active    => (S.Idle(a), unit)
            case _: S.Idle   => (S.Idle(a), unit)
          }
        } yield a
      }
    }

    class Main
    new Main with Serially[F, A] {
      def apply(f: A => F[A]) = {
        for {
          d <- Concurrent[F].deferred[Either[Throwable, Unit]]
          t  = (a: A) => {
            for {
              b <- f(a).attempt
              _ <- d.complete(b.void)
            } yield {
              b.getOrElse(a)
            }
          }
          a <- ref.modify {
            case s: S.Idle   =>
              (S.Active, start(s.value, t))
            case s: S.Active =>
              val task = (a: A) => Async[F].defer {
                for {
                  a <- s.task(a)
                  a <- t(a)
                } yield a
              }
              (S.Active(task), Concurrent[F].unit)
            case S.Active    =>
              (S.Active(t), Concurrent[F].unit)
          }
          _ <- a
          a <- d.get
          a <- a.liftTo[F]
        } yield a
      }
    }
  }
}
