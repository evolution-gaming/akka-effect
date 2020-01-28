package com.evolutiongaming.akkaeffect

import akka.actor.{ActorContext, ActorRef}
import cats.implicits._

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}

private[akkaeffect] trait SyncBehavior extends {

  def preStart(): Unit

  def receive(msg: Any, sender: ActorRef): Unit

  def postStop(): Unit
}

private[akkaeffect] object SyncBehavior {

  def empty: SyncBehavior = new SyncBehavior {

    def preStart() = {}

    def receive(msg: Any, sender: ActorRef) = {}

    def postStop() = {}
  }


  def apply[A](
    behavior: AsyncBehavior[Future, A],
    inReceive: InReceive,
    context: ActorContext
  ): SyncBehavior = {

    implicit val executor = context.dispatcher

    val self = context.self

    var stateVar = none[Future[A]]

    def syncOrAsync(future: Future[Option[A]]): Unit = {

      def stateAndFunc(a: Try[Option[A]]): (Option[A], () => Unit) = {
        a match {
          case Success(Some(a)) => (a.some, () => ())
          case Success(None)    => (none[A], () => { stateVar = none; context.stop(self) })
          case Failure(e)       => (none[A], () => { stateVar = none; throw e })
        }
      }

      future.value match {
        case Some(value) =>
          val (state, func) = stateAndFunc(value)
          stateVar = state.map { _.pure[Future] }
          func()

        case None =>
          stateVar = future
            .transform { value =>
              val (state, func) = stateAndFunc(value)
              inReceive { func() }
              state match {
                case Some(state) => state.pure[Try]
                case None        => Failure(Terminated)
              }
            }
            .some
      }
    }

    new SyncBehavior {

      def preStart() = {
        val future = behavior.preStart
        syncOrAsync(future)
      }

      def receive(msg: Any, sender: ActorRef) = {
        stateVar.foreach { state =>
          val future = behavior.receive(msg, sender, state)
          syncOrAsync(future)
        }
      }

      def postStop() = {
        stateVar.foreach { state =>
          behavior.postStop(state)
          stateVar = none
        }
      }
    }
  }


  private case object Terminated extends RuntimeException with NoStackTrace
}
