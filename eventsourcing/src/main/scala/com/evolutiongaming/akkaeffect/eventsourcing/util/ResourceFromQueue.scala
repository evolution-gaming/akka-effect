package com.evolutiongaming.akkaeffect.eventsourcing.util

import akka.stream.scaladsl.SourceQueueWithComplete
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.catshelper.FromFuture

object ResourceFromQueue {

  def apply[F[_]: Sync: FromFuture, A](
    queue: => SourceQueueWithComplete[A]
  ): Resource[F, SourceQueueWithComplete[A]] =
    Resource.make {
      Sync[F].delay(queue)
    } { queue =>
      for {
        _ <- Sync[F].delay(queue.complete())
        _ <- FromFuture[F].apply(queue.watchCompletion())
      } yield {}
    }
}
