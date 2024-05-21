package akka.persistence

import akka.actor.{ActorPath, ActorRef, ActorRefProvider, MinimalActorRef}
import cats.effect.Temporal
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.evolutiongaming.catshelper.CatsHelper.OpsCatsHelper
import com.evolutiongaming.catshelper.{SerialRef, ToTry}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Representation of actor capable of constructing result from multiple messages passed into the actor. Inspired by
  * [[PromiseActorRef]], but result [[R]] is an aggregate from incoming messages rather that first message. Can be used
  * only locally, does _not_ tolerate. [[ActorRef.provider]] and [[ActorRef.path]] functions.
  * @tparam F
  *   The effect type.
  * @tparam R
  *   The result type of the aggregate.
  */
private[persistence] trait LocalActorRef[F[_], R] {

  /** Not actual [[ActorRef]]! It is not serialisable, thus can not be passed over network. Under the hood it implements
    * [[ActorRef]] trait by providing function `!` that updates internal state using provided function `receive`. Please
    * check [[LocalActorRef.apply]] docs
    */
  def ref: ActorRef

  /** Semantically blocking while aggregating result
    */
  def res: F[R]

  /** Immediately get current state:
    *   - [[None]] if aggregating not finished yet
    *   - [[Some(Left(Throwable))]] if aggregation failed or timeout happened
    *   - [[Some(Right(r))]] if aggregation completed successfully
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
    *   [[TimeoutException]] will be thrown if no incoming messages received within the timeout.
    * @param receive
    *   The aggregate function defining how to apply incoming message on state or produce final result: [[Left]] for
    *   continue aggregating while [[Right]] for the result.
    * @tparam F
    *   The effect type.
    * @tparam S
    *   The aggregating state type.
    * @tparam R
    *   The final result type.
    * @return
    */
  def apply[F[_]: Temporal: ToTry, S, R](initial: S, timeout: FiniteDuration)(
    receive: PartialFunction[(S, M), F[Either[S, R]]],
  ): F[LocalActorRef[F, R]] = {

    val F = Temporal[F]

    type Updated = FiniteDuration

    case class State(state: S, updated: Updated)

    def timeoutException = new TimeoutException(s"no messages received during period of $timeout")

    for {
      now   <- F.monotonic
      state <- SerialRef.of[F, State](State(initial, now))
      defer <- F.deferred[Either[Throwable, R]]
      fiber <- F.start {

        type Delay = FiniteDuration

        /** If state was not updated for more than [[#timeout]] - completes [[#defer]] with failed result and exits
          * tailRecM loop.
          *
          * Otherwise calculate [[#delay]] till next timeout and continue loop.
          *
          * @param delay
          *   time before next timeout
          * @return
          *   exid or continue loop
          */
        def failOnTimeout(delay: Delay): F[Either[Delay, Unit]] =
          for {
            _     <- F.sleep(delay)
            state <- state.get
            now   <- F.monotonic
            result <-
              if (state.updated + timeout < now) defer.complete(timeoutException.asLeft) as ().asRight[Delay]
              else (state.updated + timeout - now).asLeft[Unit].pure[F]
          } yield result

        timeout.tailRecM(failOnTimeout)
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

        override def provider: ActorRefProvider = throw new UnsupportedOperationException()

        override def path: ActorPath = throw new UnsupportedOperationException()

        override def !(m: M)(implicit sender: ActorRef): Unit =
          state
            .update { s =>
              val p = s.state -> m
              if (receive.isDefinedAt(p)) {

                for {
                  t <- Temporal[F].monotonic
                  r <- receive(p)
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
