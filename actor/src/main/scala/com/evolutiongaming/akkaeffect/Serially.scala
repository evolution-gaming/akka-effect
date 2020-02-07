package com.evolutiongaming.akkaeffect

import cats.effect.Async
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._


trait Serially[F[_]] {
  /**
    * @return Outer F[_] is about `fa` enqueued, this already gives you an order guarantees,
    *         inner F[_] is about `fa` completion, happens after all previous `fa` are completed as well
    */
  def apply[A](fa: F[A]): F[F[A]]
}

object Serially {

  def of[F[_] : Async]: F[Serially[F]] = {
    Ref[F]
      .of(().pure[F])
      .map { ref =>
        new Serially[F] {
          def apply[A](fa: F[A]) = {
            for {
              d <- Deferred.uncancelable[F, Unit]
              b <- ref.modify { b => (d.get, b) }
            } yield for {
              _ <- b
              a <- fa.attempt
              _ <- d.complete(())
              a <- a.liftTo[F]
            } yield a
          }
        }
      }
  }
}
