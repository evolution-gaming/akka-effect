package com.evolutiongaming.akkaeffect

import akka.actor.{ActorRef, ActorSelection}
import akka.util.Timeout
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, FlatMap, ~>}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

/**
  * Typesafe api for so called "ask pattern"
  *
  * @see [[akka.pattern.ask]]
  *
  * @tparam A message
  * @tparam B reply
  */
trait Ask[F[_], -A, B] {

  /**
    * @return outer F[_] is about sending message, inner F[_] is about receiving reply
    */
  def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef] = None): F[F[B]]
}

object Ask {

  def const[F[_], A, B](reply: F[F[B]]): Ask[F, A, B] = (_: A, _: FiniteDuration, _: Option[ActorRef]) => reply


  def fromActorRef[F[_] : Sync : FromFuture](actorRef: ActorRef): Ask[F, Any, Any] = {
    new Ask[F, Any, Any] {

      def apply(msg: Any, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        val timeout1 = Timeout(timeout)
        val sender1 = sender getOrElse ActorRef.noSender

        Sync[F]
          .delay { akka.pattern.ask(actorRef, msg, sender1)(timeout1) }
          .map { future => FromFuture[F].apply { future } }
      }

      override def toString = {
        val path = actorRef.path
        s"Ask($path)"
      }
    }
  }


  def fromActorSelection[F[_] : Sync : FromFuture](actorSelection: ActorSelection): Ask[F, Any, Any] = {
    new Ask[F, Any, Any] {

      def apply(msg: Any, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        val timeout1 = Timeout(timeout)
        val sender1 = sender getOrElse ActorRef.noSender
        Sync[F]
          .delay { akka.pattern.ask(actorSelection, msg, sender1)(timeout1) }
          .map { future => FromFuture[F].apply { future } }
      }

      override def toString = {
        val path = actorSelection.pathString
        s"Ask($path)"
      }
    }
  }


  implicit class AskOps[F[_], A, B](val self: Ask[F, A, B]) extends AnyVal {

    def mapK[G[_] : Applicative](f: F ~> G): Ask[G, A, B] = new Ask[G, A, B] {

      def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef]) = {
        f(self(msg, timeout, sender)).map { b => f(b) }
      }

      override def toString = self.toString
    }


    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): Ask[F, A1, B1] = {
      (msg: A1, timeout: FiniteDuration, sender: Option[ActorRef]) => {
        for {
          a <- af(msg)
          b <- self(a, timeout, sender)
        } yield for {
          b <- b
          b <- bf(b)
        } yield b
      }
    }


    def narrow[A1 <: A, B1](f: B => F[B1])(implicit F: FlatMap[F]): Ask[F, A1, B1] = {
      (msg: A1, timeout: FiniteDuration, sender: Option[ActorRef]) => {
        for {
          b <- self(msg, timeout, sender)
        } yield for {
          b <- b
          b <- f(b)
        } yield b
      }
    }
  }


  implicit class AskAnyOps[F[_]](val self: Ask[F, Any, Any]) extends AnyVal {

    def typeful[A, B](f: Any => F[B])(implicit F: FlatMap[F]): Ask[F, A, B] = self.narrow[A, B](f)
  }
}