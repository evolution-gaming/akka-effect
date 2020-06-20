package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, FlatMap}

/**
  * Used during recovery to replay events
  *
  * @tparam A event
  */
trait Replay[F[_], A] {

  def apply(event: A, seqNr: SeqNr): F[Unit]
}

object Replay {

  def apply[A]: Apply[A] = new Apply[A]

  private[Replay] final class Apply[A](private val b: Boolean = true) extends AnyVal {

    def apply[F[_]](f: (A, SeqNr) => F[Unit]): Replay[F, A] = {
      (a, seqNr) => f(a, seqNr)
    }
  }


  def const[A]: ConstApply[A] = new ConstApply[A]

  private[Replay] final class ConstApply[A](private val b: Boolean = true) extends AnyVal {

    def apply[F[_]](value: F[Unit]): Replay[F, A] = (_, _) => value
  }



  def empty[F[_]: Applicative, A]: Replay[F, A] = const(().pure[F])


  implicit class ReplayOps[F[_], A](val self: Replay[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Replay[F, B] = {
      (a, seqNr) => {
        for {
          a <- f(a)
          _ <- self(a, seqNr)
        } yield {}
      }
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): Replay[F, Any] = convert(f)
  }
}