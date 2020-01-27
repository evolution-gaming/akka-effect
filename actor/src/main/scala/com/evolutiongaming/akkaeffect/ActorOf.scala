package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import cats.{Applicative, Id}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receiveOf: ReceiveOf[F, Any, Any]
  ): Actor = {

    type Rcv = Receive[F, Any, Any]

    type PostStop = F[Unit]

    type State = (Rcv, PostStop)

//    val state = StateVar[F].of(none[State])

    /*def onPreStart(adapter: ActorContextAdapter[F], self: ActorRef): Unit = {
      state.update {
        case None =>
          receiveOf(adapter.ctx)
            .allocated
            .attempt
            .flatMap {
              case Right((Some(receive), release)) =>
                (receive, release).some.pure[F]

              case Right((None, release)) =>
                release
                  .handleError { _ => () }
                  .productR {
                    adapter
                      .stop
                      .as(none[State])
                  }

              case Left(error) =>
                adapter
                  .fail(ActorError(s"$self.preStart failed: cannot allocate receive", error))
                  .as(none[State])
            }

        case Some(_) =>
          adapter
            .fail(ActorError(s"$self.preStart failed: unexpected state"))
            .as(none[State])
      }
    }*/

//    def onAny(a: Any, adapter: ActorContextAdapter[F], self: ActorRef, sender: ActorRef): Unit = {
//      state.update {
//        case Some(state @ (receive, release)) =>
//          val reply = Reply.fromActorRef[F](to = sender, from = self.some)
//          receive(a, reply)
//            .attempt
//            .flatMap {
//              case Right(false) =>
//                state
//                  .some
//                  .pure[F]
//
//              case Right(true) =>
//                release
//                  .handleError { _ => () }
//                  .productR(adapter.stop)
//                  .as(none[State])
//
//              case Left(error) =>
//                adapter
//                  .fail(error)
//                  .as(none[State])
//            }
//        case receive                          =>
//          receive.pure[F]
//      }
//    }

//    def onPostStop(): Unit = {
//      state.update {
//        case Some((_, release)) => release.as(none[State])
//        case None               => none[State].pure[F]
//      }
//    }

    new Actor {

      import context.dispatcher

      val adapter = ActorContextAdapter(context)

      var state = none[Future[State]]


      sealed abstract class Result[-A]

      object Result {

        def continue[A](a: A): Result[A] = Continue(a)

        def terminate[A](f: => Unit): Result[A] = Terminate(() => f)

        def stop[A]: Result[A] = terminate { context.stop(self) }

        def fail[A](error: Throwable): Result[A] = terminate { throw error }
        

        final case class Continue[A](a: A) extends Result[A]
        
        final case class Terminate(f: () => Unit) extends Result
      }

      final case class Run(f: () => Unit)


      def tmp[G[_] : Applicative](a: Try[Option[State]]): (Option[G[State]], () => Unit) = {
        a match {
          case Success(Some(a)) => (a.pure[G].some, () => ())
          case Success(None)    => (none[G[State]], () => {state = none/*TODO is it needed*/; context.stop(self)})
          case Failure(e)       => (none[G[State]], () => {state = none; throw e} )
        }
      }

      case object Stopped extends RuntimeException with NoStackTrace // TODO move out

      override def preStart(): Unit = {
        super.preStart()

        val self = this.self // TODO

        val future = receiveOf(adapter.ctx)
          .allocated
          .attempt
          .flatMap {
            case Right((Some(receive), release)) =>
              (receive, release).some.pure[F]

            case Right((None, release)) =>
              release
                .handleError { _ => () }
                .as(none[State])

            case Left(error) =>
              ActorError(s"$self.preStart failed to allocate receive with $error", error).raiseError[F, Option[State]]
          }
            .toFuture


        future.value match {
          case Some(value) =>
            val (s, f) = tmp[Future](value)
            state = s
            f()

          case None             =>
            state = future
              .transform { a: Try[Option[State]] =>
              val (state, f) = tmp[Id](a)
              self.tell(Run(f), self)
              state match {
                case Some(state) => state.pure[Try]
                case None         => Failure(Stopped)
              }
            }.some
        }

//          onPreStart(adapter, self)
      }

      def onAny(a: Any, self: ActorRef, sender: ActorRef): Unit = {
        state
          .foreach { state =>
            val future = FromFuture[F]
              .apply { state }
              .flatMap { case (state @ (receive, release)) =>
                val reply = Reply.fromActorRef[F](to = sender, from = self.some)
                receive(a, reply)
                  .attempt
                  .flatMap {
                    case Right(false) =>
                      state
                        .some
                        .pure[F]

                    case Right(true) =>
                      release
                        .handleError { _ => () }
                        .as(none[State])

                    case Left(error) =>
                      release
                        .handleError { _ => () }
                        .productR {
                          ActorError(s"$self.receive failed with $error", error)
                            .raiseError[F, Option[State]]
                        }
                  }
              }
              .toFuture


            future.value match {
              case Some(value) =>
                val (state, f) = tmp[Future](value)
                this.state = state
                f()

              case None =>
                this.state = future
                  .transform { a: Try[Option[State]] =>
                    val (state, f) = tmp[Id](a)
                    self.tell(Run(f), self)
                    state match {
                      case Some(state) => state.pure[Try]
                      case None         => Failure(Stopped)
                    }
                  }.some
            }

          }
      }

      def receiveTmp: Receive = { case Run(f) => f() }

      def receive: Receive = adapter.receive orElse receiveTmp orElse {
        case a => onAny(a, self = self, sender = sender())
      }

      override def postStop(): Unit = {

        state.foreach { state =>
          FromFuture[F]
            .apply { state }
            .flatMap { case (_, release) => release }
            .toFuture

          this.state = none
        }
        
        super.postStop()
      }
    }
  }
}