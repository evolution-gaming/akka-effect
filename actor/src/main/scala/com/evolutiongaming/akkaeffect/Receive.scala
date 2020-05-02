package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.Receive1.Stop

/**
  * Api for Actor.receive
  *
  * @see [[akka.actor.Actor.receive]]
  * @tparam A message
  */
trait Receive[F[_], A] {

  /**
    * Called strictly sequentially, next message will be processed only after we've done with the previous one
    * This basically preserves the semantic of Actors
    */
  def apply(msg: A, sender: ActorRef): F[Stop]
}

object Receive {

  def empty[F[_]: Applicative, A]: Receive[F, A] = const(false.pure[F])

  def stop[F[_]: Applicative, A]: Receive[F, A] = const(true.pure[F])

  def const[F[_]: Applicative, A](stop: F[Stop]): Receive[F, A] = (_, _) => stop

  def apply[F[_], A](f: (A, ActorRef) => F[Stop]): Receive[F, A] = {
    (msg, sender) => f(msg, sender)
  }

  def fromReceive[F[_]: Sync](
    receive: Receive1[F, Any, Any],
    self: ActorRef
  ): Receive[F, Any] = {
    (msg, sender) => {
      val reply = Reply.fromActorRef(to = sender, from = self.some)
      receive(msg, reply)
    }
  }


  implicit class ReceiveOps[F[_], A](val self: Receive[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Receive[G, A] = (msg, sender) => f(self(msg, sender))


    def collect[B](f: B => F[Option[A]])(implicit F: Monad[F]): Receive[F, B] = {
      (msg, sender) => {
        for {
          msg  <- f(msg)
          stop <- msg.fold(false.pure[F]) { msg => self(msg, sender) }
        } yield stop
      }
    }


    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Receive[F, B] = {
      (msg, sender) => {
        for {
          msg  <- f(msg)
          stop <- self(msg, sender)
        } yield stop
      }
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): Receive[F, Any] = convert(f)
  }
}