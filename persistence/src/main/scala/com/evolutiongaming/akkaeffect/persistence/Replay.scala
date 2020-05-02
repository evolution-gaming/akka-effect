package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, FlatMap}

/**
  * Used during recovery to replay events
  *
  * @tparam A event
  */
trait Replay[F[_], A] {

  def apply(seqNr: SeqNr, event: A): F[Unit]
}

object Replay {

  def apply[F[_], A](f: (SeqNr, A) => F[Unit]): Replay[F, A] = (seqNr, event) => f(seqNr, event)

  def const[F[_], A](value: F[Unit]): Replay[F, A] = (_, _) => value

  def empty[F[_]: Applicative, A]: Replay[F, A] = const(().pure[F])


  implicit class ReplayOps[F[_], A](val self: Replay[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Replay[F, B] = {
      (seqNr, event) => {
        for {
          e <- f(event)
          _ <- self(seqNr, e)
        } yield {}
      }
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): Replay[F, Any] = convert(f)
  }
}