package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext}
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.Duration


private[akkaeffect] trait ActorContextAdapter[F[_]] {

  def get[A](f: => A): F[A]

  def fail(error: Throwable): F[Unit]

  def receive: Actor.Receive

  def stop: F[Unit]

  def ctx: ActorCtx[F, Any, Any]
}

private[akkaeffect] object ActorContextAdapter {

  def apply[F[_] : Async : FromFuture](context: ActorContext): ActorContextAdapter[F] = {

    final case class Run(f: () => Unit)

    val self = context.self

    def run(f: => Unit): F[Unit] = {
      val run = Run(() => f)
      Sync[F].delay { self.tell(run, self) }
    }

    new ActorContextAdapter[F] {

      def fail(error: Throwable) = run { throw error }

      def get[A](f: => A): F[A] = {
        Async[F].asyncF[A] { callback =>
          run { callback(f.asRight) }
        }
      }

      val ctx = new ActorCtx[F, Any, Any] {

        val self = ActorEffect.fromActor(context.self)

        def dispatcher = context.dispatcher

        def setReceiveTimeout(timeout: Duration) = {
          run { context.setReceiveTimeout(timeout) }
        }

        def child(name: String) = {
          get { context.child(name) }
        }

        val children = get { context.children }

        val actorRefOf = ActorRefOf[F](context)
      }

      val receive = { case Run(f) => f() }

      val stop = Sync[F].delay { context.stop(self) }
    }
  }
}
