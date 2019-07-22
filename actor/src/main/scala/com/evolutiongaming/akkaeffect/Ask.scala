package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorSelection}
import akka.util.Timeout
import cats.~>
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

trait Ask[F[_], -A, B] {

  def apply(a: A, timeout: FiniteDuration, sender: Option[ActorRef] = None): F[B]
}

object Ask {

  type Any[F[_]] = Ask[F, scala.Any, scala.Any]


  def const[F[_], A, B](b: F[B]): Ask[F, A, B] = new Ask[F, A, B] {

    def apply(a: A, timeout: FiniteDuration, sender: Option[ActorRef]) = b
  }


  def fromActorRef[F[_] : FromFuture](actorRef: ActorRef): Any[F] = {
    new Any[F] {

      def apply(a: scala.Any, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        val timeout1 = Timeout(timeout)
        val sender1 = sender getOrElse ActorRef.noSender
        FromFuture[F].apply { akka.pattern.ask(actorRef, a, sender1)(timeout1) }
      }

      override def toString = {
        val path = actorRef.path
        s"Ask($path)"
      }
    }
  }


  def fromActorSelection[F[_] : FromFuture](actorSelection: ActorSelection): Any[F] = {
    new Any[F] {

      def apply(a: scala.Any, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        val timeout1 = Timeout(timeout)
        val sender1 = sender getOrElse ActorRef.noSender
        FromFuture[F].apply { akka.pattern.ask(actorSelection, a, sender1)(timeout1) }
      }

      override def toString = {
        val path = actorSelection.pathString
        s"Ask($path)"
      }
    }
  }


  implicit class AskOps[F[_], A, B](val self: Ask[F, A, B]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Ask[G, A, B] = new Ask[G, A, B] {

      def apply(a: A, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        f(self(a, timeout, sender))
      }

      override def toString = self.toString
    }


    def narrow[C <: A]: Ask[F, C, B] = self
  }
}