package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.FlatMap
import cats.effect.Sync
import com.evolutiongaming.catshelper.FromFuture

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

/**
  * Typesafe api for ActorContext
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
    }
  }


  implicit class ActorCtxAnyOps[F[_]](val self: ActorCtx[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): ActorCtx[F, A, B] = self.narrow[A, B](f)
  }
}