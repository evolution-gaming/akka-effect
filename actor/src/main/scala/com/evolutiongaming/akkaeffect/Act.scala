package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.{Async, Sync}
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

  def empty[F[_]: Sync]: Act[F] = {
    class Empty
    new Empty with Act[F] {
      def apply[A](f: => A) = Sync[F].delay { f }
    }
  }

  def serial[F[_]: Async: ToTry]: F[Act[F]] = {
    Serial
      .of[F]
      .map { serial =>
        class Serial
        new Serial with Act[F] {
          def apply[A](f: => A) = {
            serial { Sync[F].delay { f } }
              .toTry
              .get
          }
        }
      }
  }


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

    def apply[F[_]: Async](actorRef: => ActorRef): Adapter[F] = {
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

        val value = {
          class Main
          new Main with Act[F] {
            def apply[A](f: => A) = {
              for {
                adapter <- Sync[F].delay { threadLocal.get() }
                result  <- {
                  if (adapter.contains(self: Adapter[F])) {
                    Sync[F].delay { f }
                  } else {
                    Async[F].async_[A] { callback =>
                      val f1 = () => {
                        val a = Either.catchNonFatal(f)
                        callback(a)
                        a match {
                          case Right(_) =>
                          case Left(a)  => throw a
                        }
                      }
                      tell(Msg(f1))
                    }
                  }
                }
              } yield result
            }
          }
        }

        def receive(receive: Actor.Receive): Actor.Receive = {
          val receiveMsg: Actor.Receive = { case Msg(f) => f() }
          syncReceive(receiveMsg orElse receive)
        }

        def sync[A](f: => A) = {
          threadLocal.set(selfOpt)
          try f finally threadLocal.set(None)
        }
      }
    }
  }
}
