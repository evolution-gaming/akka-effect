package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Sync
import com.evolutiongaming.catshelper.FromFuture

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

// TODO add watch/unwatch
trait ActorCtx[F[_], A, B] {

  def self: ActorEffect[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorRefOf: ActorRefOf[F]
}

object ActorCtx {

  def apply[F[_] : Sync : FromFuture](
    act: Act,
    context: ActorContext
  ): ActorCtx[F, Any, Any] = {

    new ActorCtx[F, Any, Any] {

      val self = ActorEffect.fromActor(context.self)

      val dispatcher = context.dispatcher

      def setReceiveTimeout(timeout: Duration) = {
        act.ask { context.setReceiveTimeout(timeout) }
      }

      def child(name: String) = {
        act.ask { context.child(name) }
      }

      val children = act.ask { context.children }

      val actorRefOf = ActorRefOf[F](context)
    }
  }
}