package com.evolutiongaming.akkaeffect.persistence


import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._

private[akkaeffect] trait Lazy[F[_], A] {

  def get: F[A]
}

private[akkaeffect] object Lazy {

  def apply[F[_]: Concurrent]: ApplyBuilders[F] = new ApplyBuilders(Concurrent[F])


  def concurrent[F[_]: Concurrent, A](load: => F[A]): F[Lazy[F, A]] = {
    Ref[F]
      .of(none[F[A]])
      .map { ref =>
        new Lazy[F, A] {
          def get = {
            ref.get.flatMap {
              case Some(a) => a
              case None    =>
                Deferred[F, F[A]].flatMap { deferred =>
                  ref
                    .modify {
                      case Some(a) => (a.some, a)
                      case None    =>
                        val b = for {
                          a <- load.attempt
                          _ <- deferred.complete(a.liftTo[F])
                          a <- a.liftTo[F]
                        } yield a
                        val a = deferred.get.flatten.some
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


  def sync[F[_]: Sync, A](load: => F[A]): F[Lazy[F, A]] = {
    Ref[F]
      .of(none[A])
      .map { ref =>
        new Lazy[F, A] {
          def get = {
            ref.get.flatMap {
              case Some(a) => a.pure[F]
              case None    =>
                for {
                  a <- load
                  a <- ref.modify {
                    case None    => (a.some, a)
                    case Some(a) => (a.some, a)
                  }
                } yield a
            }
          }
        }
      }
  }


  final class ApplyBuilders[F[_]](val F: Concurrent[F]) extends AnyVal {

    def of[A](load: => F[A]): F[Lazy[F, A]] = Lazy.concurrent(load)(F)
  }
}
