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
    receiveOf: ReceiveOf[F, Any, Any],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = ActorOf[F](receiveOf)

    val props = Props(actor)

    actorRefOf(props, name).map { actorRef => fromActor(actorRef) }
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
      s"ActorEffect($path)"
    }
  }


  implicit class ActorRefFOps[F[_], A, B](val self: ActorEffect[F, A, B]) extends AnyVal {

    def narrow[C <: A]: ActorEffect[F, C, B] = self
  }
}