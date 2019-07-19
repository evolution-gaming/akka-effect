package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, Props}
import cats.effect.Resource

import scala.collection.immutable.Iterable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.Duration

trait ActorCtx[F[_], A, B] {

  def tell: Tell[F, A]

  def ask: Ask[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorOf(props: Props, name: Option[String] = None): Resource[F, ActorRef]
}

object ActorCtx {
  type Any[F[_]] = ActorCtx[F, scala.Any, scala.Any]
}