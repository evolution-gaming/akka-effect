package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef, ActorRefFactory}
import cats.effect.Sync
import cats.syntax.all.*
import cats.{Monad, ~>}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

/** Refined for [[ActorContext]]. Unlike the original ActorContext, all methods of ActorCtx are thread-safe
  *
  * @see
  *   [[akka.actor.ActorContext]]
  */
trait ActorCtx[F[_]] {

  /** @see
    *   [[akka.actor.ActorContext.self]]
    */
  def self: ActorRef

  /** @see
    *   [[akka.actor.ActorContext.parent]]
    */
  def parent: ActorRef

  /** @see
    *   [[akka.actor.ActorContext.dispatcher]]
    */
  def executor: ExecutionContextExecutor

  /** @see
    *   [[akka.actor.ActorContext.setReceiveTimeout]]
    */
  def setReceiveTimeout(timeout: Duration): F[Unit]

  /** @see
    *   [[akka.actor.ActorContext.child]]
    */
  def child(name: String): F[Option[ActorRef]]

  /** @see
    *   [[akka.actor.ActorContext.children]]
    */
  def children: F[List[ActorRef]]

  /** @see
    *   [[akka.actor.ActorContext.actorOf]]
    */
  def actorRefFactory: ActorRefFactory

  /** @see
    *   [[akka.actor.ActorContext.watchWith]]
    */
  def watch[A](actorRef: ActorRef, msg: A): F[Unit]

  /** @see
    *   [[akka.actor.ActorContext.unwatch]]
    */
  def unwatch(actorRef: ActorRef): F[Unit]

  /** @see
    *   [[akka.actor.ActorContext.stop]]
    */
  def stop: F[Unit]
}

object ActorCtx {

  def apply[F[_]](act: Act[F], actorContext: ActorContext): ActorCtx[F] = {
    class Main
    new Main with ActorCtx[F] {

      val self = actorContext.self

      val parent = actorContext.parent

      def executor = actorContext.dispatcher

      def setReceiveTimeout(timeout: Duration) = act(actorContext.setReceiveTimeout(timeout))

      def child(name: String) = act(actorContext.child(name))

      def children = act(actorContext.children.toList)

      def actorRefFactory: ActorRefFactory = actorContext

      def watch[A](actorRef: ActorRef, msg: A) = act { actorContext.watchWith(actorRef, msg); () }

      def unwatch(actorRef: ActorRef) = act { actorContext.unwatch(actorRef); () }

      def stop = act(actorContext.stop(actorContext.self))
    }
  }

  def apply[F[_]: Sync](actorContext: ActorContext): ActorCtx[F] = {
    class Main
    new Main with ActorCtx[F] {

      val self = actorContext.self

      val parent = actorContext.parent

      def executor = actorContext.dispatcher

      def setReceiveTimeout(timeout: Duration) = Sync[F].delay(actorContext.setReceiveTimeout(timeout))

      def child(name: String) = none[ActorRef].pure[F]

      def children = List.empty[ActorRef].pure[F]

      def actorRefFactory: ActorRefFactory = actorContext

      def watch[A](actorRef: ActorRef, msg: A) = Sync[F].delay { actorContext.watchWith(actorRef, msg); () }

      def unwatch(actorRef: ActorRef) = Sync[F].delay { actorContext.unwatch(actorRef); () }

      def stop = Sync[F].delay(actorContext.stop(actorContext.self))
    }
  }

  def flatten[F[_]: Monad](actorContext: ActorContext, actorCtx: F[ActorCtx[F]]): ActorCtx[F] = {
    class Flatten
    new Flatten with ActorCtx[F] {

      def self = actorContext.self

      def parent = actorContext.parent

      def executor = actorContext.dispatcher

      def setReceiveTimeout(timeout: Duration) = actorCtx.flatMap(_.setReceiveTimeout(timeout))

      def child(name: String) = actorCtx.flatMap(_.child(name))

      def children = actorCtx.flatMap(_.children)

      def actorRefFactory: ActorRefFactory = actorContext

      def watch[A](actorRef: ActorRef, msg: A) = actorCtx.flatMap(_.watch(actorRef, msg))

      def unwatch(actorRef: ActorRef) = actorCtx.flatMap(_.unwatch(actorRef))

      def stop = actorCtx.flatMap(_.stop)
    }
  }

  implicit class ActorCtxOps[F[_]](val actorCtx: ActorCtx[F]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): ActorCtx[G] = new ActorCtx[G] {

      def self = actorCtx.self

      def parent = actorCtx.parent

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
