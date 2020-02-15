package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.FlatMap
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


  // TODO
//  def allocate[A](resource: Resource[F, A]): F[(A, F[Unit])]
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


  implicit class ActorCtxOps[F[_], A, B](val actorCtx: ActorCtx[F, A, B]) extends AnyVal {

    // TODO refactor `narrow` methods
    def convert[A1, B1](implicit
      F: FlatMap[F],
      ca: Convert[F, A1, A],
      bd: Convert[F, B, B1]
    ): ActorCtx[F, A1, B1] = new ActorCtx[F, A1, B1] {

      def self = actorCtx.self.convert[A1, B1]

      def dispatcher = actorCtx.dispatcher

      def setReceiveTimeout(timeout: Duration) = actorCtx.setReceiveTimeout(timeout)

      def child(name: String) = actorCtx.child(name)

      def children = actorCtx.children

      def actorRefOf = actorCtx.actorRefOf
    }


    /*// TODO rename method
    def untyped[A1, B1](
    )(implicit F: FlatMap[F],
    ): ActorCtx[F, A1, B1] = new ActorCtx[F, A1, B1] {

      def self = actorCtx.self.convert[A1, B1]

      def dispatcher = actorCtx.dispatcher

      def setReceiveTimeout(timeout: Duration) = actorCtx.setReceiveTimeout(timeout)

      def child(name: String) = actorCtx.child(name)

      def children = actorCtx.children

      def actorRefOf = actorCtx.actorRefOf
    }*/
  }

  implicit class ActorCtxAnyOps[F[_]](val actorCtx: ActorCtx[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): ActorCtx[F, A, B] = new ActorCtx[F, A, B] {

      val self = actorCtx.self.typeful(f)

      def dispatcher = actorCtx.dispatcher

      def setReceiveTimeout(timeout: Duration) = actorCtx.setReceiveTimeout(timeout)

      def child(name: String) = actorCtx.child(name)

      def children = actorCtx.children

      def actorRefOf = actorCtx.actorRefOf
    }
  }
}