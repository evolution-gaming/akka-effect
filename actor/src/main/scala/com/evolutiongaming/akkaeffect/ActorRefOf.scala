package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorRefFactory, Props}
import cats.effect.{Bracket, Resource, Sync}
import cats.{Applicative, Defer, ~>}

trait ActorRefOf[F[_]] {

  def apply(
    props: Props,
    name: Option[String] = None
  ): Resource[F, ActorRef]
}

object ActorRefOf {

  def apply[F[_] : Sync](
    factory: ActorRefFactory
  ): ActorRefOf[F] = {

    (props: Props, name: Option[String]) => {

      Resource.make {
        name match {
          case Some(name) => Sync[F].delay { factory.actorOf(props, name) }
          case None       => Sync[F].delay { factory.actorOf(props) }
        }
      } { actorRef =>
        Sync[F].delay { factory.stop(actorRef) }
      }
    }
  }


  implicit class ActorRefOfOps[F[_]](val self: ActorRefOf[F]) extends AnyVal {

    def mapK[G[_]](
      f: F ~> G)(implicit
      B: Bracket[F, Throwable],
      D: Defer[G],
      G: Applicative[G]
    ): ActorRefOf[G] = {
      (props: Props, name: Option[String]) => self(props, name).mapK(f)
    }
  }
}