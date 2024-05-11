package com.evolutiongaming.akkaeffect.util

import cats.arrow.FunctionK
import cats.effect.{Ref, Sync}
import cats.syntax.all._

/** Execute underlying effect as is, but memoize a potential error.
  *
  * In other words, as soon as the error happens, the effects will not be executed anymore, but the previous error will be returned instead.
  */
trait CloseOnError[F[_]] {

  /** Wrap an effectful value.
    *
    * This method is fine to be called several times, but all of the wrapped values will use a single latch, so if one of them fails, all
    * others will return the same error.
    */
  def apply[A](fa: F[A]): F[A]

  /** Memoized error, or `None` if an error did not happen yet */
  def error: F[Option[Throwable]]
}

object CloseOnError {

  def of[F[_]: Sync]: F[CloseOnError[F]] =
    Ref[F]
      .of(none[Throwable])
      .map { errorRef =>
        new CloseOnError[F] {

          def apply[A](fa: F[A]) =
            errorRef.get
              .flatMap {
                case None =>
                  fa.onError {
                    case error =>
                      errorRef.update {
                        case None  => error.some
                        case error => error
                      }
                  }
                case Some(error) =>
                  error.raiseError[F, A]
              }

          def error = errorRef.get
        }
      }

  implicit class CloseOnErrorOps[F[_]](val self: CloseOnError[F]) extends AnyVal {

    def toFunctionK: FunctionK[F, F] = new FunctionK[F, F] {
      def apply[A](fa: F[A]): F[A] = self(fa)
    }
  }
}
