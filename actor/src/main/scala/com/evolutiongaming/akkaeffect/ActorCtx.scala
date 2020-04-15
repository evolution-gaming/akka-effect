package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.effect.{Bracket, Sync}
import cats.{Applicative, Defer, ~>}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

/**
  * Refined for ActorContext
  * Unlike the original ActorContext, all methods of ActorCtx are thread-safe
  *
  * @see [[akka.actor.ActorContext]]
  */
trait ActorCtx[F[_]] {

  /**
    * @see [[akka.actor.ActorContext.self]]
    */
  def self: ActorRef

  /**
    * @see [[akka.actor.ActorContext.parent]]
    */
  def parent: ActorRef

  /**
    * @see [[akka.actor.ActorContext.dispatcher]]
    */
  def executor: ExecutionContextExecutor

  /**
    * @see [[akka.actor.ActorContext.setReceiveTimeout]]
    */
  def setReceiveTimeout(timeout: Duration): F[Unit]

  /**
    * @see [[akka.actor.ActorContext.child]]
    */
  def child(name: String): F[Option[ActorRef]]

  /**
    * @see [[akka.actor.ActorContext.children]]
    */
  def children: F[List[ActorRef]]

  /**
    * @see [[akka.actor.ActorContext.actorOf]]
    */
  def actorRefOf: ActorRefOf[F]

  /**
    * @see [[akka.actor.ActorContext.watchWith]]
    */
  def watch[A](actorRef: ActorRef, msg: A): F[Unit]

  /**
    * @see [[akka.actor.ActorContext.unwatch]]
    */
  def unwatch(actorRef: ActorRef): F[Unit]

  /**
    * @see [[akka.actor.ActorContext.stop]]
    */
  def stop: F[Unit]
}

object ActorCtx {

  def apply[F[_]: Sync: FromFuture](
    act: Act[F],
    context: ActorContext
  ): ActorCtx[F] = {

    new ActorCtx[F] {

      val self = context.self

      val parent = context.parent

      val executor = context.dispatcher

      def setReceiveTimeout(timeout: Duration) = act { context.setReceiveTimeout(timeout) }

      def child(name: String) = act { context.child(name) }

      val children = act { context.children.toList }

      val actorRefOf = ActorRefOf[F](context)

      def watch[A](actorRef: ActorRef, msg: A) = act { context.watchWith(actorRef, msg); () }

      def unwatch(actorRef: ActorRef) = act { context.unwatch(actorRef); () }

      val stop = act { context.stop(context.self) }
    }
  }


  implicit class ActorCtxOps[F[_]](val actorCtx: ActorCtx[F]) extends AnyVal {

    def mapK[G[_]](
      f: F ~> G)(implicit
      B: Bracket[F, Throwable],
      D: Defer[G],
      G: Applicative[G]
    ): ActorCtx[G] = new ActorCtx[G] {

      val self = actorCtx.self

      val parent = actorCtx.parent

      def executor = actorCtx.executor

      def setReceiveTimeout(timeout: Duration) = f(actorCtx.setReceiveTimeout(timeout))

      def child(name: String) = f(actorCtx.child(name))

      def children = f(actorCtx.children)

      val actorRefOf = actorCtx.actorRefOf.mapK(f)

      def watch[A](actorRef: ActorRef, msg: A) = f(actorCtx.watch(actorRef, msg))

      def unwatch(actorRef: ActorRef) = f(actorCtx.unwatch(actorRef))

      def stop = f(actorCtx.stop)
    }
  }
}