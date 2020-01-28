package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Async
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

private[akkaeffect] trait AsyncBehavior[F[_], A] {

  def preStart: F[Option[A]]

  def receive(msg: Any, sender: ActorRef, state: F[A]): F[Option[A]]

  def postStop(state: F[A]): F[Unit]
}

private[akkaeffect] object AsyncBehavior {

  case class State[F[_]](receive: Receive[F, Any, Any], release: F[Unit])

  def apply[F[_] : Async : ToFuture : FromFuture](
    receiveOf: ReceiveOf[F, Any, Any],
    context: ActorContext,
    inReceive: InReceive
  ): AsyncBehavior[Future, State[F]] = {

    type S = State[F]

    val self = context.self

    new AsyncBehavior[Future, S] {

      def preStart = {
        val ctx = ActorCtx[F](inReceive, context)
        receiveOf(ctx)
          .allocated
          .attempt
          .flatMap {
            case Right((Some(receive), release)) =>
              State(receive, release)
                .some
                .pure[F]

            case Right((None, release)) =>
              release
                .handleError { _ => () }
                .as(none[S])

            case Left(error) =>
              ActorError(s"$self.preStart failed to allocate receive with $error", error)
                .raiseError[F, Option[S]]
          }
          .toFuture
      }

      def receive(msg: Any, sender: ActorRef, state: Future[S]) = {
        FromFuture[F]
          .apply { state }
          .flatMap { state =>
            val reply = Reply.fromActorRef[F](to = sender, from = self.some)
            state
              .receive(msg, reply)
              .attempt
              .flatMap {
                case Right(false) =>
                  state
                    .some
                    .pure[F]

                case Right(true) =>
                  state.release
                    .handleError { _ => () }
                    .as(none[S])

                case Left(error) =>
                  state.release
                    .handleError { _ => () }
                    .productR {
                      ActorError(s"$self.receive failed on $msg from $sender with $error", error)
                        .raiseError[F, Option[S]]
                    }
              }
          }
          .toFuture
      }

      def postStop(state: Future[S]) = {
        FromFuture[F]
          .apply { state }
          .flatMap { _.release }
          .toFuture
      }
    }
  }
}
