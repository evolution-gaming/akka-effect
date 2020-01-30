package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture



// TODO remove
private[akkaeffect] trait ActorContextAdapter[F[_]] {

  // TODO implement two cases, when in scope of receive other from future
  def get[A](f: => A): F[A]

  def stop: F[Unit]

  def ctx: ActorCtx[F, Any, Any]
}

private[akkaeffect] object ActorContextAdapter {

  def apply[F[_] : Async : FromFuture](
    act: Act,
    context: ActorContext
  ): ActorContextAdapter[F] = {

    val self = context.self

    new ActorContextAdapter[F] {

      def get[A](f: => A): F[A] = act.ask3(f).flatten

      val ctx = ActorCtx[F](act, context)

      // TODO wrong
      val stop = Sync[F].delay { context.stop(self) }
    }
  }
}
