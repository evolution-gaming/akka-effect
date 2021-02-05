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
      final case class Idle(value: A) extends S
      final case class Active(tasks: List[Task]) extends S
    }

    val ref = AtomicRef[S](S.Idle(value))

    val unitF = ().pure[F]

    val unitR = ().asRight[Throwable]

    def start(task: F[A]) = {
      task.tailRecM { task =>
        task.map { value =>
          ref.modify {
            case S.Active(tasks) if tasks.nonEmpty =>
              val task = Sync[F].suspend {
                tasks
                  .reverse
                  .foldLeft(value.pure[F]) { _ flatMap _ }
              }
              (S.Active(Nil), task.asLeft[Unit])
            case _                                 =>
              (S.Idle(value), ().asRight[F[A]])
          }
        }
      }
    }

    f => {
      Async[F].asyncF[Unit] { callback =>
        val task = (a: A) => {
          f(a)
            .flatMap { a =>
              Sync[F].delay {
                callback(unitR)
                a
              }
            }
            .handleErrorWith { error =>
              Sync[F].delay {
                callback(error.asLeft)
                a
              }
            }
        }
        ref.modify {
          case S.Idle(value)   => (S.Active(Nil), start(task(value)))
          case S.Active(tasks) => (S.Active(task :: tasks), unitF)
        }
      }
    }
  }
}
