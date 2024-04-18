package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.{Async, Resource}
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, LogOf}

object EventSourcedActorEffect {

  def of[F[_]: Async: ToFuture: FromFuture: LogOf](
    actorRefOf: ActorRefOf[F],
    eventSourcedOf: EventSourcedOf[F, EventSourcedActorOf.Lifecycle[F, Any, Any, Any]],
    persistence: EventSourcedPersistence[F, Any, Any],
    name: Option[String] = None
  ): Resource[F, ActorEffect[F, Any, Any]] = {

    def actor = EventSourcedActorOf.actor[F](eventSourcedOf, persistence)

    val props = Props(actor)

    actorRefOf(props, name)
      .map(actorRef => ActorEffect.fromActor(actorRef))
  }

}
