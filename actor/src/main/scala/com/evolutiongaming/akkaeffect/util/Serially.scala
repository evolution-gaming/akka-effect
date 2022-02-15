package com.evolutiongaming.akkaeffect.util

import cats.effect.{Async, Sync}
import cats.syntax.all._

private[akkaeffect] trait Serially[F[_], A] {
  def apply(f: A => F[A]): F[Unit]
}

private[akkaeffect] object Serially {

  def apply[F[_]: Async, A](value: A): Serially[F, A] = {

    type Task = A => F[A]

    sealed abstract class S

    object S {

      val active: S = Active(Nil)

      def active(task: Task, tasks: List[Task]): S = S.Active(task :: tasks)

      def idle(value: A): S = Idle(value)

      final case class Idle(value: A) extends S

      final case class Active(tasks: List[Task]) extends S
    }

    val ref = AtomicRef[S](S.Idle(value))

    val unitF = ().pure[F]
    val unitR0 = ().asRight[(A, List[Task])]
    val unitR1 = ().asRight[Throwable]

    def start(value: A, tasks: List[Task]) = {
      (value, tasks).tailRecM { case (value, tasks) =>
        tasks
          .reverse
          .foldLeft(value.pure[F]) { _ flatMap _ }
          .map { value =>
            ref.modify {
              case s: S.Active =>
                s.tasks match {
                  case Nil   => (S.idle(value), unitR0)
                  case tasks => (S.active, (value, tasks).asLeft[Unit])
                }
              case _: S.Idle   =>
                (S.idle(value), unitR0)
            }
          }
      }
    }

    f => {
      Async[F].async[Unit] { callback =>
        val task = (a: A) => {
          f(a)
            .map { a =>
              callback(unitR1)
              a
            }
            .handleErrorWith { error =>
              Sync[F].delay {
                callback(error.asLeft)
                a
              }
            }
        }
        ref.modify {
          case S.Idle(value)   => (S.active, start(value, List(task)).as(None))
          case S.Active(tasks) => (S.active(task, tasks), unitF.as(None))
        }
      }
    }
  }
}
