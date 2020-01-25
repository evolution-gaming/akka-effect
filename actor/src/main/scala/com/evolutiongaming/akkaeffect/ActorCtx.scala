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

  def actorRefOf: ActorRefOf[F]
}