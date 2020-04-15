package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef, ActorRefFactory}
import cats.effect.{Bracket, Sync}
import cats.implicits._
import cats.{Applicative, Defer, FlatMap, ~>}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

/**
  * Typesafe api for ActorContext
  * Unlike the ActorContext, all methods of ActorCtx are thread-safe
  *
  * @see [[akka.actor.ActorContext]]
  * @tparam A message
  * @tparam B reply
  */
trait ActorCtx[F[_], -A, B] {

  /**
    * @see [[akka.actor.ActorContext.self]]
    */
  def self: ActorEffect[F, A, B]

  /**
    * @see [[akka.actor.ActorContext.parent]]
    */
  def parent: ActorEffect[F, Any, Any]

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
  def watch(actorRef: ActorRef, msg: A): F[Unit]

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

  def apply[F[_] : Sync : FromFuture](
    act: Act[F],
    context: ActorContext
  ): ActorCtx[F, Any, Any] = {

    new ActorCtx[F, Any, Any] {

      val self = ActorEffect.fromActor(context.self)

      val parent = ActorEffect.fromActor(context.parent)

      val executor = context.dispatcher

      def setReceiveTimeout(timeout: Duration) = act { context.setReceiveTimeout(timeout) }

      def child(name: String) = act { context.child(name) }

      val children = act { context.children.toList }

      val actorRefOf = ActorRefOf[F](context)

      def watch(actorRef: ActorRef, msg: Any) = act { context.watchWith(actorRef, msg); () }

      def unwatch(actorRef: ActorRef) = act { context.unwatch(actorRef); () }

      val stop = act { context.stop(context.self) }
    }
  }


  implicit class ActorCtxOps[F[_], A, B](val actorCtx: ActorCtx[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): ActorCtx[F, A1, B1] = new ActorCtx[F, A1, B1] {

      val self = actorCtx.self.convert(af, bf)

      def parent = actorCtx.parent

      def executor = actorCtx.executor

      def setReceiveTimeout(timeout: Duration) = actorCtx.setReceiveTimeout(timeout)

      def child(name: String) = actorCtx.child(name)

      def children = actorCtx.children

      def actorRefOf = actorCtx.actorRefOf

      def watch(actorRef: ActorRef, msg: A1) = {
        for {
          msg    <- af(msg)
          result <- actorCtx.watch(actorRef, msg)
        } yield result
      }

      def unwatch(actorRef: ActorRef) = actorCtx.unwatch(actorRef)

      def stop = actorCtx.stop
    }


    def narrow[A1 <: A, B1](
      f: B => F[B1])(implicit
      F: FlatMap[F]
    ): ActorCtx[F, A1, B1] = new ActorCtx[F, A1, B1] {

      val self = actorCtx.self.narrow(f)

      def parent = actorCtx.parent

      def executor = actorCtx.executor

      def setReceiveTimeout(timeout: Duration) = actorCtx.setReceiveTimeout(timeout)

      def child(name: String) = actorCtx.child(name)

      def children = actorCtx.children

      def actorRefOf = actorCtx.actorRefOf

      def watch(actorRef: ActorRef, msg: A1) = actorCtx.watch(actorRef, msg)

      def unwatch(actorRef: ActorRef) = actorCtx.unwatch(actorRef)

      def stop = actorCtx.stop
    }


    def mapK[G[_]](
      f: F ~> G)(implicit
      B: Bracket[F, Throwable],
      D: Defer[G],
      G: Applicative[G]
    ): ActorCtx[G, A, B] = new ActorCtx[G, A, B] {

      val self = actorCtx.self.mapK(f)

      val parent = actorCtx.parent.mapK(f)

      def executor = actorCtx.executor

      def setReceiveTimeout(timeout: Duration) = f(actorCtx.setReceiveTimeout(timeout))

      def child(name: String) = f(actorCtx.child(name))

      def children = f(actorCtx.children)

      val actorRefOf = actorCtx.actorRefOf.mapK(f)

      def watch(actorRef: ActorRef, msg: A) = f(actorCtx.watch(actorRef, msg))

      def unwatch(actorRef: ActorRef) = f(actorCtx.unwatch(actorRef))

      def stop = f(actorCtx.stop)
    }
  }


  implicit class ActorCtxAnyOps[F[_]](val self: ActorCtx[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): ActorCtx[F, A, B] = self.narrow[A, B](f)
  }
}