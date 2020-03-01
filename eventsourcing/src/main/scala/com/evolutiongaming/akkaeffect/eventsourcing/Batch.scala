package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.implicits._


private[akkaeffect] trait Batch[F[_], S, A, B] {

  /**
    * @return outer F[_] is about enqueuing, inner F[_] is accomplishment
    */
  def apply(a: A): F[F[B]]
}

private[akkaeffect] object Batch {

  /**
    * This runs f strictly serially and keeps stashing A meanwhile,
    * when f done with previous batch, it will proceed with next one
    */
  def of[F[_] : Concurrent, S, A, B](
    s: S)(
    f: (S, Nel[A]) => F[(S, B)]
  ): F[Batch[F, S, A, B]] = {

    case class E(a: A, d: Deferred[F, Either[Throwable, B]])

    Ref[F]
      .of(s.asLeft[List[E]])
      .map { ref =>

        def start(s: S, e: E): F[Unit] = {
          (s, Nel.of(e))
            .tailRecM[F, Unit] { case (s, es0) =>
              val es = es0.reverse
              val as = es.map { _.a }
              for {
                t  <- f(s, as).attempt
                s1  = t.fold(_ => s, { case (a, _) => a })
                b   = t.map { case (_, b) => b }
                _  <- es.foldMapM { _.d.complete(b) }
                e  <- ref.modify {
                  case Right(e :: es) => (List.empty[E].asRight, (s1, Nel(e, es)).asLeft)
                  case _              => (s1.asLeft, ().asRight)
                }
              } yield e
            }
            .start
            .void
        }

        (a: A) => {
          for {
            d <- Deferred.uncancelable[F, Either[Throwable, B]]
            e  = E(a, d)
            r <- ref.modify {
              case Right(es) => ((e :: es).asRight, ().pure[F])
              case Left(s)   => (List.empty[E].asRight, start(s, e))
            }
            _ <- r
          } yield for {
            b <- d.get
            b <- b.liftTo[F]
          } yield b
        }
      }
  }


  def apply[F[_] : Concurrent]: ApplyBuilders[F] = new ApplyBuilders(Concurrent[F])


  final class ApplyBuilders[F[_]](val F: Concurrent[F]) extends AnyVal {

    def of[S, A, B](s: S)(f: (S, Nel[A]) => F[(S, B)]): F[Batch[F, S, A, B]] = {
      Batch.of(s)(f)(F)
    }
  }
}
