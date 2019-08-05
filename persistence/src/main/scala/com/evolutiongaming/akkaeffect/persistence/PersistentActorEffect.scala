package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.{Async, Resource}
import com.evolutiongaming.akkaeffect.{ActorCtx, ActorEffect, ActorRefOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}


object PersistentActorEffect {

  def of[F[_] : Async : ToFuture : FromFuture : ToTry](
    actorRefOf: ActorRefOf[F],
    setup: ActorCtx[F, Any, Any] => F[PersistenceSetup[F, Any, Any, Any]],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = PersistentActorOf[F](setup)

    val props = Props(actor)

    val actorRef = actorRefOf(props, name)

    for {
      actorRef <- actorRef
    } yield {
      ActorEffect.fromActor(actorRef)
    }
  }
}
