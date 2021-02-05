package com.evolutiongaming.akkaeffect

import akka.actor.{ActorPath, ActorRef, Props}
import cats.effect.{Concurrent, Resource, Sync}
import cats.{Applicative, FlatMap, ~>}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

/**
  * Typesafe api for ActorRef
  *
  * @see [[akka.actor.ActorRef]]
  * @tparam A message
  * @tparam B reply
  */
trait ActorEffect[F[_], -A, B] {

  /**
    * @see [[akka.actor.ActorRef.path]]
    */
  def path: ActorPath

  /**
    * @see [[akka.pattern.ask]]
    */
  def ask: Ask[F, A, B]

  /**
    * @see [[akka.actor.ActorRef.tell]]
    */
  def tell: Tell[F, A]

  /**
    * @return underlying ActorRef
    */
  def toUnsafe: ActorRef
}

object ActorEffect {

  def of[F[_]: Concurrent: ToFuture: FromFuture](
    actorRefOf: ActorRefOf[F],
    receiveOf: ReceiveOf[F, Call[F, Any, Any], ActorOf.Stop],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = ActorOf[F](receiveOf.toReceiveOfEnvelope)

    val props = Props(actor)

    actorRefOf(props, name).map { actorRef => fromActor(actorRef) }
  }


  def fromActor[F[_]: Sync: FromFuture](
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


    def narrow[A1 <: A, B1](
      f: B => F[B1])(implicit
      F: FlatMap[F]
    ): ActorEffect[F, A1, B1] = new ActorEffect[F, A1, B1] {

      def path = self.path

      val ask = self.ask.narrow[A1, B1](f)

      val tell = self.tell

      def toUnsafe = self.toUnsafe
    }


    def mapK[G[_]: Applicative](f: F ~> G): ActorEffect[G, A, B] = new ActorEffect[G, A, B] {

      def path = self.path

      val ask = self.ask.mapK(f)

      val tell = self.tell.mapK(f)

      def toUnsafe = self.toUnsafe
    }
  }


  implicit class ActorEffectAnyOps[F[_]](val self: ActorEffect[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): ActorEffect[F, A, B] = self.narrow(f)
  }
}