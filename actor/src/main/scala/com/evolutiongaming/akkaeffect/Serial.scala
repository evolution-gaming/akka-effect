package com.evolutiongaming.akkaeffect

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.implicits._


// TODO move out to cats-helper
/**
  * Runs `fa` strictly serially, somehow similar to actor's semantic
  */
private[akkaeffect] trait Serial[F[_]] {
  /**
    * @return Outer F[_] is about `fa` enqueued, this already gives you an order guarantees,
    *         inner F[_] is about `fa` completion, happens after all previous `fa` are completed as well
    */
  def apply[A](fa: F[A]): F[F[A]]
}

private[akkaeffect] object Serial {

  def of[F[_] : Concurrent]: F[Serial[F]] = {
    Ref[F]
      .of(().pure[F])
      .map { ref =>
        new Serial[F] {
          def apply[A](fa: F[A]) = {
            val result = for {
              p <- Deferred[F, Unit]
              b <- ref.modify { b => (p.get, b) }
              a  = for {
                _ <- b
                a <- fa.attempt
                _ <- p.complete(())
                a <- a.liftTo[F]
              } yield a
              a <- a.start
            } yield {
              a.join
            }
            result.uncancelable
          }
        }
      }
  }
}
