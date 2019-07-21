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

    new ActorRefOf[F] {
      
      def apply(props: Props, name: Option[String]) = {

        Resource.make {
          Sync[F].delay {
            name.fold {
              factory.actorOf(props)
            } { name =>
              factory.actorOf(props, name)
            }
          }
        } { actorRef =>
          Sync[F].delay { factory.stop(actorRef) }
        }
      }
    }
  }
}