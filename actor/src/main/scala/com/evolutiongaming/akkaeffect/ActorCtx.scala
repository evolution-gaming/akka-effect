package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

trait ActorCtx[F[_], A, B] {

  def self: ActorEffect[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorRefOf: ActorRefOf[F]
}

object ActorCtx {

  def apply[F[_] : Async : FromFuture](
    inReceive: InReceive,
    context: ActorContext
  ): ActorCtx[F, Any, Any] = {

    def tell(f: => Unit): F[Unit] = {
      Sync[F].delay { inReceive { f } }
    }

    def ask[A](f: => A): F[A] = {
      Async[F].asyncF[A] { callback =>
        tell { callback(f.asRight) }
      }
    }

    new ActorCtx[F, Any, Any] {

      val self = ActorEffect.fromActor(context.self)

      val dispatcher = context.dispatcher

      def setReceiveTimeout(timeout: Duration) = {
        tell { context.setReceiveTimeout(timeout) }
      }

      def child(name: String) = {
        ask { context.child(name) }
      }

      val children = ask { context.children }

      val actorRefOf = ActorRefOf[F](context)
    }
  }
}