package com.evolutiongaming.akkaeffect.persistence


import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.implicits._
import com.evolutiongaming.akkaeffect.PromiseEffect
import com.evolutiongaming.catshelper.FromFuture

private[akkaeffect] trait Lazy[F[_], A] {

  def get: F[A]
}

private[akkaeffect] object Lazy {

  def apply[F[_] : Sync]: ApplyBuilders[F] = new ApplyBuilders(Sync[F])


  def of[F[_] : Sync : FromFuture, A](load: => F[A]): F[Lazy[F, A]] = {
    Ref[F]
      .of(none[F[A]])
      .map { ref =>
        new Lazy[F, A] {
          def get = {
            ref.get.flatMap {
              case Some(a) => a
              case None    =>
                PromiseEffect[F, A].flatMap { promise =>
                  ref
                    .modify {
                      case Some(a) => (a.some, a)
                      case None    =>
                        val b = for {
                          a <- load.attempt
                          _ <- promise.complete(a)
                          a <- a.liftTo[F]
                        } yield a
                        val a = promise.get.some
                        (a, b)
                    }
                    .flatten
                    .uncancelable
                }
            }
          }
        }
      }
  }


  final class ApplyBuilders[F[_]](val F: Sync[F]) extends AnyVal {

    def of[A](load: => F[A])(implicit fromFuture: FromFuture[F]): F[Lazy[F, A]] = Lazy.of(load)(F, fromFuture)
  }
}
