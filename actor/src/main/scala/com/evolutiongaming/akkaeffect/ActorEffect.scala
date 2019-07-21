package com.evolutiongaming.akkaeffect

import akka.actor.{ActorPath, ActorRef, Props}
import cats.effect.{Async, Resource, Sync}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

trait ActorEffect[F[_], -A, B] { self =>

  def path: ActorPath

  def ask: Ask[F, A, B]

  def tell: Tell[F, A]

  def toUnsafe: ActorRef
}

object ActorEffect {

  def of[F[_] : Async : ToFuture : FromFuture](
    actorRefOf: ActorRefOf[F],
    create: ActorCtx.Any[F] => F[Option[Receive.Any[F]]],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = ActorOf[F](create)

    val props = Props(actor)

    val actorRef = actorRefOf(props, name)

    for {
      actorRef <- actorRef
    } yield {
      fromActor(actorRef)
    }
  }


  def fromActor[F[_] : Sync : FromFuture](
    actorRef: ActorRef
  ): ActorEffect[F, Any, Any] = new ActorEffect[F, Any, Any] {

    def path = actorRef.path

    val ask = Ask.fromActorRef[F](actorRef)

    val tell = Tell.fromActorRef[F](actorRef)

    def toUnsafe = actorRef

    override def toString = {
      val path = actorRef.path
      s"ActorRefF($path)"
    }
  }


  implicit class ActorRefFOps[F[_], A, B](val self: ActorEffect[F, A, B]) extends AnyVal {

    def narrow[C <: A]: ActorEffect[F, C, B] = self
  }
}