package com.evolutiongaming.akkaeffect

import akka.actor.{ActorPath, ActorRef, ActorRefFactory, Props}
import cats.effect.{Async, Resource, Sync}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

trait ActorRefF[F[_], -A, B] { self =>

  def path: ActorPath

  def ask: Ask[F, A, B]

  def tell: Tell[F, A]

  def toUnsafe: ActorRef
}

object ActorRefF {

  def of[F[_] : Async : ToFuture : FromFuture](
    factory: ActorRefFactory,
    create: ActorCtx.Any[F] => F[Option[Receive.Any[F]]],
    name: Option[String] = None
  ): Resource[F, ActorRefF[F, Any, Any]] = {

    def actor = ActorOf[F](create)

    val props = Props(actor)

    val actorRef = Resource.make {
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

    for {
      actorRef <- actorRef
    } yield {
      fromActor(actorRef)
    }
  }


  def fromActor[F[_] : Sync : FromFuture](
    actorRef: ActorRef
  ): ActorRefF[F, Any, Any] = new ActorRefF[F, Any, Any] {

    def path = actorRef.path

    val ask = Ask.fromActorRef[F](actorRef)

    val tell = Tell.fromActorRef[F](actorRef)

    def toUnsafe = actorRef

    override def toString = {
      val path = actorRef.path
      s"ActorRefF($path)"
    }
  }


  implicit class ActorRefFOps[F[_], A, B](val self: ActorRefF[F, A, B]) extends AnyVal {

    def narrow[C <: A]: ActorRefF[F, C, B] = self
  }
}