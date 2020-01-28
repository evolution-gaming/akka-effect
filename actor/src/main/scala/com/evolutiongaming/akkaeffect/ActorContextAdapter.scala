package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext}
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.Duration


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
    inReceive: InReceive,
    context: ActorContext
  ): ActorContextAdapter[F] = {

    val self = context.self

    def run(f: => Unit): F[Unit] = {
      Sync[F].delay { inReceive { f } }
    }

    new ActorContextAdapter[F] {

      def fail(error: Throwable) = run { throw error }

      def get[A](f: => A): F[A] = {
        Async[F].asyncF[A] { callback =>
          run { callback(f.asRight) }
        }
      }

      val ctx = ActorCtx[F](inReceive, context)

      // TODO wrong
      val stop = Sync[F].delay { context.stop(self) }
    }
  }
}
