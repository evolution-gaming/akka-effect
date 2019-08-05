package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

trait ActorCtx[F[_], A, B] {

  def self: ActorEffect[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorOf: ActorRefOf[F]
}


object ActorCtx {

  /*implicit class ActorCtxOps[F[_], A, B](val self: ActorCtx[F, A, B]) extends AnyVal {

    def untype: ActorCtx[F, Any, Any] = {

      val self1 = self

      new ActorCtx[F, Any, Any] {

        def self = ???

        def dispatcher = self1.dispatcher

        def setReceiveTimeout(timeout: Duration) = self1.setReceiveTimeout(timeout)

        def child(name: String) = self1.child(name)

        def children = self1.children

        def actorOf = ???
      }
    }
  }*/
}