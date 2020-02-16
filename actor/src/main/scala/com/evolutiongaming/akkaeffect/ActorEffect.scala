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


    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F]
    ): ActorEffect[F, A1, B1] = new ActorEffect[F, A1, B1] {

      def path = self.path

      val ask = self.ask.convert(af, bf)

      val tell = self.tell.convert(af)

      def toUnsafe = self.toUnsafe
    }
  }


  implicit class ActorEffectAnyOps[F[_]](val self: ActorEffect[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): ActorEffect[F, A, B] = new ActorEffect[F, A, B] {

      def path = self.path

      val ask = self.ask.typeful(f)

      val tell = self.tell

      def toUnsafe = self.toUnsafe
    }
  }
}