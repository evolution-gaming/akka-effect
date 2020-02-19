package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * Act executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act[F[_]] {

  def apply[A](f: => A): F[A]
}


private[akkaeffect] object Act {

  def now[F[_] : Sync]: Act[F] = new Act[F] {
    def apply[A](f: => A) = Sync[F].delay { f }
  }


  def of[F[_] : Sync : ToTry : ToFuture : FromFuture]: F[Act[F]] = {
    Serially.of[F].map { serially => apply(serially) }
  }


  def apply[F[_] : Sync : ToTry](serially: Serially[F]): Act[F] = new Act[F] {
    def apply[A](f: => A) = {
      serially { Sync[F].delay { f } }
        .toTry
        .get
    }
  }


  def adapter(actorRef: ActorRef): Adapter = {
    val tell = actorRef.tell(_, ActorRef.noSender)
    adapter(tell)
  }


  def adapter(tell: Any => Unit): Adapter = {

    case class Msg(f: () => Unit)

    val actorThread = new ThreadLocal[Boolean] {
      override def initialValue() = false
    }

    new Adapter {

      def syncReceive(receive: Actor.Receive): Actor.Receive = new Actor.Receive {

        def isDefinedAt(a: Any) = receive.isDefinedAt(a)

        def apply(a: Any) = sync { receive(a) }
      }

      val value = new Act[Future] {
        def apply[A](f: => A) = {
          if (actorThread.get()) {
            Future.successful(f)
          } else {
            val promise = Promise[A]
            val f1 = () => {
              val a = Try(f)
              promise.complete(a)
              a.void.get
            }
            tell(Msg(f1))
            promise.future
          }
        }
      }

      def receive(receive: Actor.Receive) = {
        val receiveMsg: Actor.Receive = { case Msg(f) => f() }
        syncReceive(receiveMsg orElse receive)
      }

      def sync[A](f: => A) = {
        actorThread.set(true)
        try f finally actorThread.set(false)
      }
    }
  }

          
  implicit class ActFutureOps(val self: Act[Future]) extends AnyVal {

    def fromFuture[F[_] : FromFuture]: Act[F] = new Act[F] {
      def apply[A](f: => A) = FromFuture[F].apply { self(f) }
    }
  }


  trait Adapter {

    def value: Act[Future]

    def receive(receive: Actor.Receive): Actor.Receive

    def sync[A](f: => A): A
  }
}
