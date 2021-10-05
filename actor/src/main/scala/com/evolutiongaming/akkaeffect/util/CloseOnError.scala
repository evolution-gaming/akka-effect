package com.evolutiongaming.akkaeffect.util

import cats.arrow.FunctionK
import cats.effect.Sync
import cats.syntax.all._
import cats.effect.Ref


trait CloseOnError[F[_]] {

  def apply[A](fa: F[A]): F[A]

  def error: F[Option[Throwable]]
}

object CloseOnError {

  def of[F[_]: Sync]: F[CloseOnError[F]] = {
    Ref[F]
      .of(none[Throwable])
      .map { errorRef =>
        new CloseOnError[F] {

          def apply[A](fa: F[A]) = {
            errorRef
              .get
              .flatMap {
                case None        =>
                  fa.onError { case error =>
                    errorRef.update {
                      case None  => error.some
                      case error => error
                    }
                  }
                case Some(error) =>
                  error.raiseError[F, A]
              }
          }

          def error = errorRef.get
        }
      }
  }


  implicit class CloseOnErrorOps[F[_]](val self: CloseOnError[F]) extends AnyVal {

    def toFunctionK: FunctionK[F, F] = new FunctionK[F, F] {
      def apply[A](fa: F[A]): F[A] = self(fa)
    }
  }
}