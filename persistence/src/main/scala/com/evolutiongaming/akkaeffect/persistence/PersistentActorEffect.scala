package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.{Concurrent, Resource}
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}


object PersistentActorEffect {

  // TODO not use Any
  def of[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorRefOf: ActorRefOf[F],
    persistenceSetupOf: PersistenceSetupOf[F, Any, Any, Any, Any],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = PersistentActorOf[F](persistenceSetupOf)

    val props = Props(actor)

    actorRefOf(props, name)
      .map { actorRef => ActorEffect.fromActor(actorRef) }
  }
}
