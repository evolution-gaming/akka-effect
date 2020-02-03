package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorRefFactory, Props}
import cats.effect.{Resource, Sync}

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
}