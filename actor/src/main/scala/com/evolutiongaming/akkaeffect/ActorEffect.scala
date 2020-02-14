package com.evolutiongaming.akkaeffect

import akka.actor.{ActorPath, ActorRef, Props}
import cats.FlatMap
import cats.effect.{Async, Resource, Sync}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

trait ActorEffect[F[_], -A/*TODO remove variance everywhere*/, B] { self =>

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


  implicit class ActorEffectOps[F[_], A, B](val self: ActorEffect[F, A, B]) extends AnyVal {

    def narrow[C <: A]: ActorEffect[F, C, B] = self


    def convert[C, D](implicit
      F: FlatMap[F],
      ca: Convert[F, C, A],
      bd: Convert[F, B, D]
    ): ActorEffect[F, C, D] = new ActorEffect[F, C, D] {

      def path = self.path

      val ask = self.ask.convert[C, D]

      val tell = self.tell.convert[C]

      def toUnsafe = self.toUnsafe
    }
  }
}