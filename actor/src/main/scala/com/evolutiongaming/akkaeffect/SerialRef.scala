package com.evolutiongaming.akkaeffect

import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}


/**
  * Similar [[cats.effect.concurrent.Ref]], however supports effectful modification
  */
private[akkaeffect] trait SerialRef[F[_], A] {

  def update[B](f: A => F[A]): F[F[Unit]]

  def modify[B](f: A => F[(A, B)]): F[F[B]]
}

private[akkaeffect] object SerialRef {

  def of[F[_] : Sync : ToFuture : FromFuture, A](a: A): F[SerialRef[F, A]] = {
    Ref[F]
      .of(a.pure[F])
      .map { ref =>
        new SerialRef[F, A] {

          def update[B](f: A => F[A]) = {
            modify { a => f(a).map { a => (a, ()) } }
          }

          def modify[B](f: A => F[(A, B)]) = {
            val result = for {
              p <- PromiseEffect[F, A]
              a <- ref.modify { a => (p.get, a) }
              b  = for {
                a  <- a
                ab <- f(a).attempt
                a   = ab.map { case (a, _) => a }
                b   = ab.map { case (_, b) => b }
                _  <- p.complete(a)
                b  <- b.liftTo[F]
              } yield b
              b <- b.startNow
            } yield b
            result.uncancelable
          }
        }
      }
  }


  def apply[F[_] : Concurrent]: ApplyBuilders[F] = new ApplyBuilders(Concurrent[F])


  final class ApplyBuilders[F[_]](val F: Sync[F]) extends AnyVal {

    def of[A](a: A)(implicit toFuture: ToFuture[F], fromFuture: FromFuture[F]): F[SerialRef[F, A]] = {
      SerialRef.of(a)(F, toFuture, fromFuture)
    }
  }
}
