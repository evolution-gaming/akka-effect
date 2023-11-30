package akka.persistence

import akka.actor.{ActorRef, MinimalActorRef}
import cats.effect.{Async, Deferred}
import cats.syntax.all._
import com.evolutiongaming.catshelper.CatsHelper.OpsCatsHelper
import com.evolutiongaming.catshelper.{SerialRef, ToFuture}

trait LocalActorRef[F[_], R] {

  def ref: ActorRef

  def res: F[R]

  def get: F[Option[Either[Throwable, R]]]
}

object LocalActorRef {

  type M = Any

  // TODO: implement also blocking impl based on ToTry instead of ToFuture   
  def apply[F[_]: Async: ToFuture, S, R](initial: S)(receive: (S, M) => F[Either[S, R]]): F[LocalActorRef[F, R]] =
    for {
      state <- SerialRef.of[F, S](initial)
      defer <- Deferred[F, Either[Throwable, R]]
    } yield new LocalActorRef[F, R] {

      override def ref: ActorRef = new MinimalActorRef {

        override def provider = throw new UnsupportedOperationException()

        override def path = throw new UnsupportedOperationException()

        override def !(m: M)(implicit sender: ActorRef): Unit = {

          val _ = state
            .update { s =>
              receive(s, m).flatMap {
                case Left(s)  => s.pure[F]
                case Right(r) => defer.complete(r.asRight).as(s)
              }
            }
            .handleErrorWith { e =>
              defer.complete(e.asLeft).void
            }
            .toFuture

        }
      }

      override def res: F[R] = defer.get.flatMap(_.liftTo[F])

      override def get: F[Option[Either[Throwable, R]]] = defer.tryGet
    }
  
}
