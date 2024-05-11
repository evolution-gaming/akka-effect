package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import cats.effect.{Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/** AskFrom is similar to `ask`, however enables to pass `sender` as part of outgoing message
  */
trait AskFrom[F[_]] {

  def apply[A, B: ClassTag](to: ActorRef)(f: ActorRef => A): F[F[B]]
}

object AskFrom {

  def of[F[_]: Sync: FromFuture](
    actorRefOf: ActorRefOf[F],
    from: ActorRef,
    timeout: FiniteDuration
  ): Resource[F, AskFrom[F]] = {

    final case class Msg(to: ActorRef, f: ActorRef => Any)

    def actor() = new Actor {
      def receive = {
        case Msg(to, f) => to.tell(f(sender()), sender())
      }
    }

    val props = Props(actor())
    actorRefOf(props).map { actorRef =>
      val timeout1 = Timeout(timeout)

      new AskFrom[F] {
        def apply[A, B: ClassTag](to: ActorRef)(f: ActorRef => A) =
          Sync[F]
            .delay(akka.pattern.ask(actorRef, Msg(to, f), from)(timeout1))
            .map(future => FromFuture[F].apply(future.mapTo[B]))
      }
    }
  }
}
