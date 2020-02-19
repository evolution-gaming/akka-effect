package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.{Concurrent, Resource}
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}


object PersistentActorEffect {

  def of[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorRefOf: ActorRefOf[F],
    eventSourcedOf: EventSourcedOf[F, Any, Any, Any, Any],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = PersistentActorOf[F](eventSourcedOf)

    val props = Props(actor)

    actorRefOf(props, name)
      .map { actorRef => ActorEffect.fromActor(actorRef) }
  }
}
