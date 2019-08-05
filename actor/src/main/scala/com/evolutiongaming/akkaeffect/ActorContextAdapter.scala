package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext}
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.Duration


trait ActorContextAdapter[F[_]] {

  def run(f: => Unit): F[Unit]

  def get[A](f: => A): F[A]

  def receive: Actor.Receive

  def stop: F[Unit]

  def ctx: ActorCtx[F, Any, Any]
}

object ActorContextAdapter {

  def apply[F[_] : Async : FromFuture](context: ActorContext): ActorContextAdapter[F] = {

    final case class Run(f: () => Unit)

    val self = context.self

    new ActorContextAdapter[F] {

      def run(f: => Unit): F[Unit] = {
        val run = Run(() => f)
        Sync[F].delay { self.tell(run, self) }
      }

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

        val actorOf = ActorRefOf[F](context)
      }

      val receive = { case Run(f) => f() }

      val stop = Sync[F].delay { context.stop(self) }
    }
  }
}
