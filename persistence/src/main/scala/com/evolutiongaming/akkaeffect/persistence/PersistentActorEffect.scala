package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.{Async, Resource}
import com.evolutiongaming.akkaeffect.{ActorCtx, ActorEffect, ActorRefOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}


object PersistentActorEffect {

  def of[F[_] : Async : ToFuture : FromFuture : ToTry](
    actorRefOf: ActorRefOf[F],
    persistenceSetupOf: PersistenceSetupOf[F, Any, Any, Any, Any],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = PersistentActorOf[F](persistenceSetupOf)

    val props = Props(actor)

    val actorRef = actorRefOf(props, name)

    for {
      actorRef <- actorRef
    } yield {
      ActorEffect.fromActor(actorRef)
    }
  }
}
