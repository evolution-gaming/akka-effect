package com.evolutiongaming.akkaeffect.persistence

import cats.syntax.all._
import cats.{Applicative, FlatMap, ~>}
import com.evolutiongaming.akkaeffect.Fail
import com.evolutiongaming.catshelper.{Log, MeasureDuration, MonadThrowable}

/**
  * @see [[akka.persistence.Eventsourced.deleteMessages]]
  */
trait DeleteEventsTo[F[_]] {

  /**
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def apply(seqNr: SeqNr): F[F[Unit]]
}

object DeleteEventsTo {

  def empty[F[_]: Applicative]: DeleteEventsTo[F] = const(().pure[F].pure[F])

  def const[F[_]](value: F[F[Unit]]): DeleteEventsTo[F] = {
    class Const
    new Const with DeleteEventsTo[F] {
      def apply(seqNr: SeqNr) = value
    }
  }

  private sealed abstract class WithLogging

  private sealed abstract class WithFail

  private sealed abstract class MapK


  implicit class DeleteEventsToOps[F[_]](val self: DeleteEventsTo[F]) extends AnyVal {

    def mapK[G[_]: Applicative](f: F ~> G): DeleteEventsTo[G] = {
      new MapK with DeleteEventsTo[G] {
        def apply(seqNr: SeqNr) = f(self(seqNr)).map { a => f(a) }
      }
    }

    def withLogging1(
      log: Log[F])(implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F]
    ): DeleteEventsTo[F] = {
      new WithLogging with DeleteEventsTo[F] {
        def apply(seqNr: SeqNr) = {
          for {
            d <- MeasureDuration[F].start
            r <- self(seqNr)
          } yield for {
            r <- r
            d <- d
            _ <- log.info(s"delete events to $seqNr in ${ d.toMillis }ms")
          } yield r
        }
      }
    }

    def withFail(
      fail: Fail[F])(implicit
      F: MonadThrowable[F]
    ): DeleteEventsTo[F] = {

      new WithFail with DeleteEventsTo[F] {
        def apply(seqNr: SeqNr) = {
          fail.adapt(s"failed to delete events to $seqNr") {
            self(seqNr)
          }
        }
      }
    }
  }
}