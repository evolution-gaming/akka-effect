package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorSelection}
import akka.util.Timeout
import cats.implicits._
import cats.{Applicative, FlatMap, ~>}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

trait Ask[F[_], -A, B] {

  // TODO F[Either[
  def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef] = None): F[B]
}

object Ask {

  def const[F[_], A, B](reply: F[B]): Ask[F, A, B] = (_: A, _: FiniteDuration, _: Option[ActorRef]) => reply


  def fromActorRef[F[_] : FromFuture](actorRef: ActorRef): Ask[F, Any, Any] = {
    new Ask[F, Any, Any] {

      def apply(msg: Any, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        val timeout1 = Timeout(timeout)
        val sender1 = sender getOrElse ActorRef.noSender
        FromFuture[F].apply { akka.pattern.ask(actorRef, msg, sender1)(timeout1) }
      }

      override def toString = {
        val path = actorRef.path
        s"Ask($path)"
      }
    }
  }


  def fromActorSelection[F[_] : FromFuture](actorSelection: ActorSelection): Ask[F, Any, Any] = {
    new Ask[F, Any, Any] {

      def apply(msg: Any, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        val timeout1 = Timeout(timeout)
        val sender1 = sender getOrElse ActorRef.noSender
        FromFuture[F].apply { akka.pattern.ask(actorSelection, msg, sender1)(timeout1) }
      }

      override def toString = {
        val path = actorSelection.pathString
        s"Ask($path)"
      }
    }
  }


  implicit class AskOps[F[_], A, B](val self: Ask[F, A, B]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Ask[G, A, B] = new Ask[G, A, B] {

      def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        f(self(msg, timeout, sender))
      }

      override def toString = self.toString
    }


    def narrow[C <: A]: Ask[F, C, B] = self


    def convert[A1, B1](
      fa: A1 => F[A],
      fb: B => F[B1])(implicit
      F: FlatMap[F],
    ): Ask[F, A1, B1] = {
      (msg: A1, timeout: FiniteDuration, sender: Option[ActorRef]) => {
        for {
          a <- fa(msg)
          b <- self(a, timeout, sender)
          d <- fb(b)
        } yield d
      }
    }
  }


  implicit class AskAnyOps[F[_]](val self: Ask[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): Ask[F, A, B] = {
      (msg: A, timeout: FiniteDuration, sender: Option[ActorRef]) => {
        for {
          b <- self(msg, timeout, sender)
          b <- f(b)
        } yield b
      }
    }
  }
}