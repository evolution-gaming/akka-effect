package com.evolutiongaming.akkaeffect.eventsourcing

import cats.syntax.all._
import cats.kernel.Semigroup
import cats.{Applicative, FlatMap, Functor, Monad}
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.catshelper.MonadThrowable

/**
  * This function will be executed after events are stored
  */
trait Effect[F[_], A] {

  /**
    * @param seqNr - either last seqNr or error if failed to store events
    */
  def apply(seqNr: Either[Throwable, SeqNr]): F[A]
}

object Effect {

  def empty[F[_]: Applicative]: Effect[F, Unit] = const(().pure[F])

  def const[F[_], A](fa: F[A]): Effect[F, A] = _ => fa

  def apply[F[_], A](f: Either[Throwable, SeqNr] => F[A]): Effect[F, A] = seqNr => f(seqNr)

  def right[F[_]: MonadThrowable, A](f: SeqNr => F[A]): Effect[F, A] = Effect { _.liftTo[F].flatMap(f) }

  def rightConst[F[_]: MonadThrowable, A](fa: => F[A]): Effect[F, A] = right { _ => fa }


  implicit def monadEffect[F[_]: Monad]: Monad[Effect[F, *]] = new Monad[Effect[F, *]] {

    override def map[A, B](fa: Effect[F, A])(f: A => B) = {
      fa.map(f)
    }

    def flatMap[A, B](fa: Effect[F, A])(f: A => Effect[F, B]) = {
      Effect { seqNr => fa(seqNr).flatMap { a => f(a)(seqNr) } }
    }

    def tailRecM[A, B](a: A)(f: A => Effect[F, Either[A, B]]) = {
      Effect { seqNr => a.tailRecM { a => f(a)(seqNr) } }
    }

    def pure[A](a: A) = Effect[F, A] { _ => a.pure[F] }
  }


  implicit def semigroupEffect[F[_]: Monad, A: Semigroup]: Semigroup[Effect[F, A]] = {
    (a, b) => a.flatMap { a => b.map { b => a.combine(b) } }
  }


  implicit class EffectOps[F[_], A](val self: Effect[F, A]) extends AnyVal {

    def mapM[B](f: A => F[B])(implicit F: FlatMap[F]): Effect[F, B] = {
      seqNr => self(seqNr).flatMap(f)
    }

    def map[B](f: A => B)(implicit F: Functor[F]): Effect[F, B] = {
      seqNr => self(seqNr).map(f)
    }
  }
}