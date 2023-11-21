package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.SnapshotterInterop
import cats.effect.Sync
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

object SnapshotterOf {

  private[akkaeffect] def apply[F[_]: Sync: FromFuture, A](
    snapshotter: akka.persistence.Snapshotter,
    timeout: FiniteDuration
  ): Snapshotter[F, A] = {
    SnapshotterInterop(snapshotter, timeout)
  }

}
