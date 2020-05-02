package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.Receive.Stop

// TODO rename
trait ReceiveAny[F[_], A] {

  /**
    * Called strictly sequentially, next message will be processed only after we've done with the previous one
    * This basically preserves the semantic of Actors
    */
  def apply(msg: A, sender: ActorRef): F[Stop]
}

object ReceiveAny {

  def empty[F[_]: Applicative, A]: ReceiveAny[F, A] = const(false.pure[F])

  def stop[F[_]: Applicative, A]: ReceiveAny[F, A] = const(true.pure[F])

  def const[F[_]: Applicative, A](stop: F[Stop]): ReceiveAny[F, A] = (_, _) => stop

  def apply[F[_], A](f: (A, ActorRef) => F[Stop]): ReceiveAny[F, A] = {
    (msg, sender) => f(msg, sender)
  }

  def fromReceive[F[_]: Sync](
    receive: Receive[F, Any, Any],
    self: ActorRef
  ): ReceiveAny[F, Any] = {
    (msg, sender) => {
      val reply = Reply.fromActorRef(to = sender, from = self.some)
      receive(msg, reply)
    }
  }


  implicit class ReceiveOps[F[_], A](val self: ReceiveAny[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): ReceiveAny[G, A] = (msg, sender) => f(self(msg, sender))


    def collect[B](f: B => F[Option[A]])(implicit F: Monad[F]): ReceiveAny[F, B] = {
      (msg, sender) => {
        for {
          msg  <- f(msg)
          stop <- msg.fold(false.pure[F]) { msg => self(msg, sender) }
        } yield stop
      }
    }


    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): ReceiveAny[F, B] = {
      (msg, sender) => {
        for {
          msg  <- f(msg)
          stop <- self(msg, sender)
        } yield stop
      }
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): ReceiveAny[F, Any] = convert(f)
  }
}