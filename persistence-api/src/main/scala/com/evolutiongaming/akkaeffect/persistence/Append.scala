package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.Fail
import com.evolutiongaming.catshelper.{Log, MeasureDuration, MonadThrowable}

trait Append[F[_], -A] {

  /**
    * @param events to be saved, inner Nel[A] will be persisted atomically, outer Nel[_] is for batching
    * @return SeqNr of last event
    */
  def apply(events: Events[A]): F[F[SeqNr]]
}

object Append {

  def const[F[_], A](seqNr: F[F[SeqNr]]): Append[F, A] = {
    class Const
    new Const with Append[F, A] {
      def apply(events: Events[A]) = seqNr
    }
  }

  def empty[F[_]: Applicative, A]: Append[F, A] =
    const(SeqNr.Min.pure[F].pure[F])

  implicit class AppendOps[F[_], A](val self: Append[F, A]) extends AnyVal {

    def mapK[G[_]: Applicative](f: F ~> G): Append[G, A] = { events =>
      f(self(events)).map { a =>
        f(a)
      }
    }

    def convert[B](f: B => F[A])(implicit F: Monad[F]): Append[F, B] = {
      events =>
        {
          for {
            events <- events.traverse(f)
            seqNr <- self(events)
          } yield seqNr
        }
    }

    def narrow[B <: A]: Append[F, B] = events => self(events)

    def withLogging1(log: Log[F])(
      implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F]
    ): Append[F, A] = events => {
      for {
        d <- MeasureDuration[F].start
        r <- self(events)
      } yield
        for {
          r <- r
          d <- d
          _ <- log.debug(s"append ${events.size} events in ${d.toMillis}ms")
        } yield r
    }

    def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Append[F, A] = {
      events =>
        fail.adapt(s"failed to append $events") { self(events) }
    }
  }

}
