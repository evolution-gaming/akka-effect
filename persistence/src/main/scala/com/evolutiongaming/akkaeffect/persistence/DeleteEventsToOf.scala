package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.DeleteEventsToInterop
import cats.effect.{Resource, Sync}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

object DeleteEventsToOf {

  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    */
  private[akkaeffect] def of[F[_]: Sync: FromFuture, A](
    persistentActor: akka.persistence.PersistentActor,
    timeout: FiniteDuration
  ): Resource[F, DeleteEventsTo[F]] = {
    DeleteEventsToInterop(persistentActor, timeout)
  }

}
