package com.evolutiongaming.akkaeffect

import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.Promise
import scala.util.Try


private[akkaeffect] trait PromiseEffect[F[_], A] {

  def get: F[A]

  def complete(a: Try[A]): F[Unit]
}


// TODO test
private[akkaeffect] object PromiseEffect {

  /**
    * Unlike `Deferred.uncancelable`, `complete` method does not add async boundary
    */
  def apply[F[_] : Sync : FromFuture, A]: F[PromiseEffect[F, A]] = {
    Sync[F]
      .delay { Promise[A]() }
      .map { promise =>
        new PromiseEffect[F, A] {

          def get: F[A] = FromFuture[F].apply { promise.future }

          def complete(a: Try[A]): F[Unit] = Sync[F].delay { promise.complete(a) }
        }
      }
  }


  implicit class PromiseFOps[F[_], A](val self: PromiseEffect[F, A]) extends AnyVal {

    def complete(a: Either[Throwable, A]): F[Unit] = self.complete(a.liftTo[Try])

    def success(a: A): F[Unit] = self.complete(a.pure[Try])

    def fail(a: Throwable): F[Unit] = self.complete(a.raiseError[Try, A])
  }
}
