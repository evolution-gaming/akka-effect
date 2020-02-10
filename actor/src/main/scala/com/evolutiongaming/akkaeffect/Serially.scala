package com.evolutiongaming.akkaeffect

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}


private[akkaeffect] trait Serially[F[_]] {
  /**
    * @return Outer F[_] is about `fa` enqueued, this already gives you an order guarantees,
    *         inner F[_] is about `fa` completion, happens after all previous `fa` are completed as well
    */
  def apply[A](fa: F[A]): F[F[A]]
}

private[akkaeffect] object Serially {

  def of[F[_] : Sync : ToFuture : FromFuture]: F[Serially[F]] = {
    Ref[F]
      .of(().pure[F])
      .map { ref =>
        new Serially[F] {
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
              f <- Sync[F].delay { a.toFuture }
            } yield {
              FromFuture[F].apply { f }
            }
          }
        }
      }
  }
}
