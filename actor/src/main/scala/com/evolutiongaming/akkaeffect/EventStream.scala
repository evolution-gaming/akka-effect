package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.reflect.ClassTag

trait EventStream[F[_]] {

  def publish[A](a: A): F[Unit]

  def subscribe[A](onEvent: A => F[Unit])(implicit tag: ClassTag[A]): Resource[F, Unit]
}

object EventStream {

  def apply[F[_]: Sync: ToFuture: FromFuture](actorSystem: ActorSystem): EventStream[F] = {
    apply(actorSystem.eventStream, actorSystem)
  }

  def apply[F[_]: Sync: ToFuture: FromFuture](
    eventStream: akka.event.EventStream,
    refFactory: ActorRefFactory,
  ): EventStream[F] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](refFactory)

    new EventStream[F] {

      def publish[A](a: A) = {
        Sync[F].delay { eventStream.publish(a) }
      }

      def subscribe[A](onEvent: A => F[Unit])(implicit tag: ClassTag[A]) = {

        val channel = tag.runtimeClass

        def unsubscribe(actorRef: ActorRef): F[Unit] = {
          Sync[F].delay { eventStream.unsubscribe(actorRef, channel) }.void
        }

        def receiveOf = ReceiveOf[F] { actorCtx =>
          Resource.make {
            Receive[Envelope[Any]] { envelope =>
              tag
                .unapply(envelope.msg)
                .foldMapM { msg =>
                  onEvent(msg).handleError { _ =>
                    ()
                  }
                }
                .as(false)
            } {
              false.pure[F]
            }.pure[F]
          } { _ =>
            unsubscribe(actorCtx.self)
          }
        }

        def subscribe(actorRef: ActorRef) = {
          Resource.make {
            Sync[F].delay { eventStream.subscribe(actorRef, channel) }.void
          } { _ =>
            unsubscribe(actorRef)
          }
        }

        val props = Props(ActorOf(receiveOf))
        for {
          actorRef <- actorRefOf(props)
          result   <- subscribe(actorRef)
        } yield result
      }
    }
  }
}