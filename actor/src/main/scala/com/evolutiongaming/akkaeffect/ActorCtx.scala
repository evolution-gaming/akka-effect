package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Sync
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

  def apply[F[_] : Sync : FromFuture](
    act: Act,
    context: ActorContext
  ): ActorCtx[F, Any, Any] = {

    new ActorCtx[F, Any, Any] {

      val self = ActorEffect.fromActor(context.self)

      val dispatcher = context.dispatcher

      def setReceiveTimeout(timeout: Duration) = {
        act.ask3 { context.setReceiveTimeout(timeout) }.void
      }

      def child(name: String) = {
        act.ask3 { context.child(name) }.flatten
      }

      val children = act.ask3 { context.children }.flatten

      val actorRefOf = ActorRefOf[F](context)
    }
  }
}