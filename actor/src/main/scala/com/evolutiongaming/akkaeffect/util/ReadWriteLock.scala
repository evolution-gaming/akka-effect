package com.evolutiongaming.akkaeffect.util

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

// TODO implement and move out
private[akkaeffect] trait ReadWriteLock[F[_]] {
  import ReadWriteLock._

  def read: Lock[F]

  def write: Lock[F]
}

private[akkaeffect] object ReadWriteLock {

  def of[F[_]: Sync]: F[ReadWriteLock[F]] = {

    case class State()

    Ref
      .of(State())
      .map { ref =>
        new ReadWriteLock[F] {

          val read = new Lock[F] {
            def apply[A](fa: F[A]): F[F[A]] = ???
          }

          val write = new Lock[F] {
            def apply[A](fa: F[A]): F[F[A]] = ???
          }
        }
      }
  }


  trait Lock[F[_]] {

    def apply[A](fa: F[A]): F[F[A]]
  }
}
