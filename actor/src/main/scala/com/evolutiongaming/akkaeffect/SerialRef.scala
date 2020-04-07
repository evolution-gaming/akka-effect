package com.evolutiongaming.akkaeffect

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}


/**
  * Similar [[cats.effect.concurrent.Ref]], however supports effectful modification
  */
private[akkaeffect] trait SerialRef[F[_], A] {

  def get: F[A]

  def modify[B](f: A => F[(A, B)]): F[F[B]]

  def update[B](f: A => F[A]): F[F[Unit]]
}

private[akkaeffect] object SerialRef {

  def of[F[_]: Sync: ToFuture: FromFuture, A](a: A): F[SerialRef[F, A]] = {
    for {
      serial    <- Serial.of[F]
      serialRef <- of(serial, a)
    } yield serialRef
  }


  def of[F[_] : Sync, A](serial: Serial[F], a: A): F[SerialRef[F, A]] = {
    Ref[F]
      .of(a)
      .map { ref =>
        new SerialRef[F, A] {

          def get = ref.get

          def modify[B](f: A => F[(A, B)]) = {
            serial {
              for {
                a      <- get
                ab     <- f(a)
                (a, b)  = ab
                _      <- ref.set(a)
              } yield b
            }
          }

          def update[B](f: A => F[A]) = {
            modify { a => f(a).map { a => (a, ()) } }
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