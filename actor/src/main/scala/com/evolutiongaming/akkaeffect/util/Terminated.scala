package com.evolutiongaming.akkaeffect.util

import akka.actor.{Actor, ActorRef, Props}
import cats.effect.Concurrent
import cats.effect.concurrent.Deferred
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.ToFuture

trait Terminated[F[_]] {

  def apply(actorRef: ActorRef): F[Unit]

  def apply[A, B](actorEffect: ActorEffect[F, A, B]): F[Unit]
}

object Terminated {

  def apply[F[_]: Concurrent: ToFuture](
    actorRefOf: ActorRefOf[F]
  ): Terminated[F] = {
    new Terminated[F] {

      def apply(actorRef: ActorRef) = {
        Deferred[F, Unit].flatMap { deferred =>
          def actor() = {
            new Actor {

              override def preStart() = {
                context.watch(actorRef)
                ()
              }

              def receive = {
                case akka.actor.Terminated(`actorRef`) =>
                  deferred
                    .complete(())
                    .toFuture
                  ()
              }
            }
          }

          actorRefOf(Props(actor())).use { _ => deferred.get }
        }
      }

      def apply[A, B](actorEffect: ActorEffect[F, A, B]) = {
        apply(actorEffect.toUnsafe)
      }
    }
  }
}
