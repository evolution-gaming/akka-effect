package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.DeleteEventsToInterop
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import cats.{Applicative, FlatMap, ~>}
import com.evolutiongaming.akkaeffect.Fail
import com.evolutiongaming.catshelper.{FromFuture, Log, MonadThrowable}
import com.evolutiongaming.smetrics.MeasureDuration

import scala.concurrent.duration.FiniteDuration

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

  def empty[F[_] : Applicative]: DeleteEventsTo[F] = const(().pure[F].pure[F])

  def const[F[_]](value: F[F[Unit]]): DeleteEventsTo[F] = (_: SeqNr) => value


  private[akkaeffect] def of[F[_] : Sync : FromFuture : Fail, A](
    persistentActor: akka.persistence.PersistentActor,
    timeout: FiniteDuration
  ): Resource[F, DeleteEventsTo[F]] = {
    DeleteEventsToInterop(persistentActor, timeout)
  }


  implicit class DeleteEventsToOps[F[_]](val self: DeleteEventsTo[F]) extends AnyVal {

    def mapK[G[_]: Applicative](f: F ~> G): DeleteEventsTo[G] = {
      seqNr => f(self(seqNr)).map { a => f(a) }
    }

    def withLogging(
      log: Log[F])(implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F]
    ): DeleteEventsTo[F] = {
      seqNr => {
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

    def withFail(
      fail: Fail[F])(implicit
      F: MonadThrowable[F]
    ): DeleteEventsTo[F] = {
      seqNr => {
        fail.adapt(s"failed to delete events to $seqNr") {
          self(seqNr)
        }
      }
    }
  }
}