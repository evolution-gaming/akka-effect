package com.evolutiongaming.akkaeffect.persistence


import cats.Id
import cats.effect.Async
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.implicits._

// TODO test this
trait Lazy[F[_], A] {

  def apply(): F[A]
}

object Lazy {

  /**
    * is not synchronised, safe to use within actor
    */
  def unsafe[A](f: => A): Lazy[Id, A] = {
    var a = none[A]
    () =>
      a.getOrElse {
        val b = f
        a = b.some
        b
      }
  }


  def apply[F[_] : Async]: ApplyBuilders[F] = new ApplyBuilders(Async[F])


  def of[F[_] : Async, A](load: => F[A]): F[Lazy[F, A]] = {
    Ref[F]
      .of(none[F[A]])
      .map { ref =>
        () => {
          ref.get.flatMap {
            case Some(a) => a
            case None    =>
              Deferred
                .uncancelable[F, Either[Throwable, A]]
                .flatMap { deferred =>
                  ref
                    .modify {
                      case Some(a) => (a.some, a)
                      case None    =>
                        val b = for {
                          a <- load.attempt
                          _ <- deferred.complete(a)
                          a <- a.liftTo[F]
                        } yield a
                        val a = deferred.get.rethrow.some
                        (a, b)
                    }
                    .flatten
                    .uncancelable
                }
          }
        }
      }
  }


  final class ApplyBuilders[F[_]](val F: Async[F]) extends AnyVal {

    def of[A](load: => F[A]): F[Lazy[F, A]] = Lazy.of(load)(F)
  }
}
