package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.DeleteEventsToInterop
import cats.effect.{Resource, Sync}
import com.evolutiongaming.catshelper.{FromFuture, MonadThrowable}

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

  def apply[F[_] : Sync : FromFuture : Fail, A](
    persistentActor: akka.persistence.PersistentActor,
    timeout: FiniteDuration
  ): Resource[F, DeleteEventsTo[F]] = {
    DeleteEventsToInterop(persistentActor, timeout)
  }


  implicit class DeleteEventsToOps[F[_]](val self: DeleteEventsTo[F]) extends AnyVal {

    def withFail(
      fail: Fail[F])(implicit
      F: MonadThrowable[F]
    ): DeleteEventsTo[F] = new DeleteEventsTo[F] {

      def apply(seqNr: SeqNr) = {
        fail.adapt(s"failed to delete events to $seqNr") {
          self(seqNr)
        }
      }
    }
  }
}