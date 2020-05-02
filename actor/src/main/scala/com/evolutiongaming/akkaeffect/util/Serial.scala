package com.evolutiongaming.akkaeffect.util

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}


// TODO move out to cats-helper
/**
  * Runs `fa` strictly serially, somehow similar to actor's semantic
  */
private[akkaeffect] trait Serial[F[_]] {
  /**
    * @return outer F[_] is about `fa` enqueued, this already gives you an order guarantees,
    *         inner F[_] is about `fa` completion, happens after all previous `fa` are completed as well
    */
  def apply[A](fa: F[A]): F[F[A]]
}

private[akkaeffect] object Serial {

  def of[F[_]: Sync: ToFuture: FromFuture]: F[Serial[F]] = {
    Ref[F]
      .of(().pure[F])
      .map { ref =>
        new Serial[F] {
          def apply[A](fa: F[A]) = {
            for {
              p <- PromiseEffect[F, Unit]
              b <- ref.modify { b => (p.get, b) }
              a  = for {
                _ <- b
                a <- fa.attempt
                _ <- p.success(())
                a <- a.liftTo[F]
              } yield a
              a <- a.startNow
            } yield a
          }
        }
      }
  }
}
