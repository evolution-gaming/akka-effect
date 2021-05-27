package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef, ActorRefFactory}
import cats.effect.Sync
import cats.~>

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
  def actorRefFactory: ActorRefFactory

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


  def apply[F[_]: Sync](act: Act[F], actorContext: ActorContext): ActorCtx[F] = {

    new ActorCtx[F] {

      val self = actorContext.self

      val parent = actorContext.parent

      val executor = actorContext.dispatcher

      def setReceiveTimeout(timeout: Duration) = act { actorContext.setReceiveTimeout(timeout) }

      def child(name: String) = act { actorContext.child(name) }

      val children = act { actorContext.children.toList }

      def actorRefFactory = actorContext

      def watch[A](actorRef: ActorRef, msg: A) = act { actorContext.watchWith(actorRef, msg); () }

      def unwatch(actorRef: ActorRef) = act { actorContext.unwatch(actorRef); () }

      val stop = act { actorContext.stop(actorContext.self) }
    }
  }


  implicit class ActorCtxOps[F[_]](val actorCtx: ActorCtx[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): ActorCtx[G] = new ActorCtx[G] {

      val self = actorCtx.self

      val parent = actorCtx.parent

      def executor = actorCtx.executor

      def setReceiveTimeout(timeout: Duration) = f(actorCtx.setReceiveTimeout(timeout))

      def child(name: String) = f(actorCtx.child(name))

      def children = f(actorCtx.children)

      def actorRefFactory = actorCtx.actorRefFactory

      def watch[A](actorRef: ActorRef, msg: A) = f(actorCtx.watch(actorRef, msg))

      def unwatch(actorRef: ActorRef) = f(actorCtx.unwatch(actorRef))

      def stop = f(actorCtx.stop)
    }
  }
}