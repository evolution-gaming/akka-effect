package com.evolutiongaming.akkaeffect

import akka.actor.ActorContext
import cats.effect.{Async, Sync}
import com.evolutiongaming.catshelper.FromFuture



// TODO remove
private[akkaeffect] trait ActorContextAdapter[F[_]] {

  // TODO implement two cases, when in scope of receive other from future
  def get[A](f: => A): F[A]

  def fail(error: Throwable): F[Unit]

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

      def fail(error: Throwable) = act.tell1 { throw error }

      def get[A](f: => A): F[A] = act.ask(f)

      val ctx = ActorCtx[F](act, context)

      // TODO wrong
      val stop = Sync[F].delay { context.stop(self) }
    }
  }
}
