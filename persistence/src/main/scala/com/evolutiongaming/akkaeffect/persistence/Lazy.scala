package com.evolutiongaming.akkaeffect.persistence


import cats.Id
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.PromiseEffect
import com.evolutiongaming.catshelper.FromFuture

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


  def of[F[_] : Sync : FromFuture, A](load: => F[A]): F[Lazy[F, A]] = {
    Ref[F]
      .of(none[F[A]])
      .map { ref =>
        () => {
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


  final class ApplyBuilders[F[_]](val F: Sync[F]) extends AnyVal {

    def of[A](load: => F[A])(implicit fromFuture: FromFuture[F]): F[Lazy[F, A]] = Lazy.of(load)(F, fromFuture)
  }
}
