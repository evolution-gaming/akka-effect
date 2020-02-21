package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.{Applicative, Defer, FlatMap, ~>}
import cats.effect.{Bracket, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.collection.immutable.Iterable
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
    * @see [[akka.actor.ActorContext.dispatcher]]
    */
  def dispatcher: ExecutionContextExecutor

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
  def children: F[Iterable[ActorRef]]

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
}

object ActorCtx {

  def apply[F[_] : Sync : FromFuture](
    act: Act[F],
    context: ActorContext
  ): ActorCtx[F, Any, Any] = {

    new ActorCtx[F, Any, Any] {

      val self = ActorEffect.fromActor(context.self)

      val dispatcher = context.dispatcher

      def setReceiveTimeout(timeout: Duration) = {
        act { context.setReceiveTimeout(timeout) }
      }

      def child(name: String) = {
        act { context.child(name) }
      }

      val children = act { context.children }

      val actorRefOf = ActorRefOf[F](context)

      def watch(actorRef: ActorRef, msg: Any) = act { context.watchWith(actorRef, msg); () }

      def unwatch(actorRef: ActorRef) = act { context.unwatch(actorRef); () }
    }
  }


  implicit class ActorCtxOps[F[_], A, B](val actorCtx: ActorCtx[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): ActorCtx[F, A1, B1] = new ActorCtx[F, A1, B1] {

      val self = actorCtx.self.convert(af, bf)

      def dispatcher = actorCtx.dispatcher

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
    }


    def narrow[A1 <: A, B1](
      f: B => F[B1])(implicit
      F: FlatMap[F]
    ): ActorCtx[F, A1, B1] = new ActorCtx[F, A1, B1] {

      val self = actorCtx.self.narrow(f)

      def dispatcher = actorCtx.dispatcher

      def setReceiveTimeout(timeout: Duration) = actorCtx.setReceiveTimeout(timeout)

      def child(name: String) = actorCtx.child(name)

      def children = actorCtx.children

      def actorRefOf = actorCtx.actorRefOf

      def watch(actorRef: ActorRef, msg: A1) = actorCtx.watch(actorRef, msg)

      def unwatch(actorRef: ActorRef) = actorCtx.unwatch(actorRef)
    }


    def mapK[G[_]](
      f: F ~> G)(implicit
      B: Bracket[F, Throwable],
      D: Defer[G],
      G: Applicative[G]
    ): ActorCtx[G, A, B] = new ActorCtx[G, A, B] {

      val self = actorCtx.self.mapK(f)

      def dispatcher = actorCtx.dispatcher

      def setReceiveTimeout(timeout: Duration) = f(actorCtx.setReceiveTimeout(timeout))

      def child(name: String) = f(actorCtx.child(name))

      def children = f(actorCtx.children)

      val actorRefOf = actorCtx.actorRefOf.mapK(f)

      def watch(actorRef: ActorRef, msg: A) = f(actorCtx.watch(actorRef, msg))

      def unwatch(actorRef: ActorRef) = f(actorCtx.unwatch(actorRef))
    }
  }


  implicit class ActorCtxAnyOps[F[_]](val self: ActorCtx[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): ActorCtx[F, A, B] = self.narrow[A, B](f)
  }
}