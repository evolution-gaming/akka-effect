package akka.persistence

import akka.actor.ActorRef
import akka.actor.MinimalActorRef
import cats.effect.Temporal
import cats.effect.syntax.all._
import cats.syntax.all._
import com.evolutiongaming.catshelper.CatsHelper.OpsCatsHelper
import com.evolutiongaming.catshelper.SerialRef
import com.evolutiongaming.catshelper.ToTry

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Representation of actor capable of constructing result from multiple messages passed into the actor. Inspired by [[PromiseActorRef]] but
  * result [[R]] is an aggregate from incomming messages rather that first message. Can be used only locally, does _not_ tolerate.
  * [[ActorRef.provider]] and [[ActorRef.path]] functions.
  * @tparam F
  *   The effect type.
  * @tparam R
  *   The result type of the aggregate.
  */
private[persistence] trait LocalActorRef[F[_], R] {

  def ref: ActorRef

  /** Semantically blocking while aggregating result
    */
  def res: F[R]

  /** Immidiately get currect state:
    *
    * \- [[None]] if aggregating not finished yet
    *
    * \- [[Some(Left(Throwable))]] if aggregation failed or timeout happened
    *
    * \- [[Some(Right(r))]] if aggregation completed successfully
    */
  def get: F[Option[Either[Throwable, R]]]
}

private[persistence] object LocalActorRef {

  type M = Any

  /** Create new [[LocalActorRef]]
    *
    * @param initial
    *   The initial state of type [[S]].
    * @param timeout
    *   [[TimeoutException]] will be thrown if no incomming messages received within the timeout.
    * @param receive
    *   The aggregate function defining how to apply incomming message on state or produce final result: [[Left]] for continue aggregating
    *   while [[Right]] for the result.
    * @tparam F
    *   The effect type.
    * @tparam S
    *   The aggregating state type.
    * @tparam R
    *   The final result type.
    * @return
    */
  def apply[F[_]: Temporal: ToTry, S, R](initial: S, timeout: FiniteDuration)(
    receive: PartialFunction[(S, M), F[Either[S, R]]]
  ): F[LocalActorRef[F, R]] = {

    val F = Temporal[F]

    case class State(state: S, updated: Instant)

    def timeoutException = new TimeoutException(s"no messages received during period of $timeout")

    for {
      now   <- F.realTimeInstant
      state <- SerialRef.of[F, State](State(initial, now))
      defer <- F.deferred[Either[Throwable, R]]
      fiber <- F.start {
        val f = for {
          _ <- F.sleep(timeout)
          s <- state.get
          n <- F.realTimeInstant
          c  = s.updated.plus(timeout.toNanos, ChronoUnit.NANOS).isBefore(n)
          _ <- if (c) defer.complete(timeoutException.asLeft) else F.unit
        } yield c

        ().tailRecM { _ =>
          f.ifF(().asRight, ().asLeft)
        }
      }
    } yield new LocalActorRef[F, R] {

      private def done(e: Either[Throwable, R]) = {
        val finish = for {
          _ <- defer.complete(e)
          _ <- fiber.cancel
        } yield {}
        finish.uncancelable
      }

      override def ref: ActorRef = new MinimalActorRef {

        override def provider = throw new UnsupportedOperationException()

        override def path = throw new UnsupportedOperationException()

        override def !(m: M)(implicit sender: ActorRef): Unit =
          state
            .update { s =>
              if (receive.isDefinedAt(s.state -> m)) {

                for {
                  t <- Temporal[F].realTimeInstant
                  r <- receive(s.state -> m)
                  s <- r match {
                    case Left(s)  => State(s, t).pure[F]
                    case Right(r) => done(r.asRight).as(s)
                  }
                } yield s

              } else {
                s.pure[F]
              }
            }
            .handleErrorWith { e =>
              done(e.asLeft).void
            }
            .toTry
            .get

      }

      override def res: F[R] = defer.get.flatMap(_.liftTo[F])

      override def get: F[Option[Either[Throwable, R]]] = defer.tryGet
    }
  }

}
