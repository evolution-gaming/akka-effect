package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.{Async, Concurrent, Sync}
import cats.syntax.all._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{Serial, ToTry}

/**
  * Act executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act[F[_]] {

  def apply[A](f: => A): F[A]
}


private[akkaeffect] object Act {

  private sealed abstract class Empty

  def empty[F[_]: Sync]: Act[F] = new Empty with Act[F] {
    def apply[A](f: => A) = Sync[F].delay { f }
  }


  private sealed abstract class Serial1

  def serial[F[_]: Concurrent: ToTry]: F[Act[F]] = {
    Serial
      .of[F]
      .map { serial =>
        new Serial1 with Act[F] {
          def apply[A](f: => A) = {
            serial { Sync[F].delay { f } }
              .toTry
              .get
          }
        }
      }
  }


  private sealed abstract class Adapter1

  sealed trait AdapterLike

  trait Adapter[F[_]] extends AdapterLike {

    def value: Act[F]

    def receive(receive: Actor.Receive): Actor.Receive

    def sync[A](f: => A): A
  }

  object Adapter {

    private val threadLocal: ThreadLocal[Option[AdapterLike]] = new ThreadLocal[Option[AdapterLike]] {
      override def initialValue() = none[AdapterLike]
    }

    def apply[F[_]: Async](actorRef: ActorRef): Adapter[F] = {
      val tell = actorRef.tell(_, ActorRef.noSender)
      apply(tell)
    }


    def apply[F[_]: Async](tell: Any => Unit): Adapter[F] = {

      case class Msg(f: () => Unit)

      new Adapter[F] { self =>

        private val selfOpt = self.some

        def syncReceive(receive: Actor.Receive): Actor.Receive = new Actor.Receive {

          def isDefinedAt(a: Any) = receive.isDefinedAt(a)

          def apply(a: Any) = sync { receive(a) }
        }

        val value = new Adapter1 with Act[F] {
          def apply[A](f: => A) = {
            if (threadLocal.get().contains(self: Adapter[F])) {
              Sync[F].delay { f }
            } else {
              Async[F].async[A] { callback =>
                val f1 = () => {
                  val a = Either.catchNonFatal(f)
                  callback(a)
                  a match {
                    case Right(_) =>
                    case Left(a) => throw a
                  }
                }
                tell(Msg(f1))
              }
            }
          }
        }

        def receive(receive: Actor.Receive) = {
          val receiveMsg: Actor.Receive = { case Msg(f) => f() }
          syncReceive(receiveMsg orElse receive)
        }

        def sync[A](f: => A) = {
          threadLocal.set(selfOpt)
          try f finally threadLocal.set(none)
        }
      }
    }
  }
}
