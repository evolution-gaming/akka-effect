package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}
import cats.implicits._
import cats.kernel.Eq
import cats.{Apply, Eval, NonEmptyTraverse, Order, Show}

/**
  *
  * @param values inner Nel[A] will be persisted atomically,
  *               This applies some restrictions on persistence layer.
  *               Please do not overuse this feature
  * @tparam A event
  */
final case class Events[+A](values: Nel[Nel[A]]) { self =>

  override def toString = {
    Events
      .show(Show.fromToString[A])
      .show(self)
  }
}

object Events {

  implicit val traverseEvents: NonEmptyTraverse[Events] = new NonEmptyTraverse[Events] {

    def nonEmptyTraverse[G[_], A, B](fa: Events[A])(f: A => G[B])(implicit G: Apply[G]) = {
      fa
        .values
        .nonEmptyTraverse { _.nonEmptyTraverse(f) }
        .map { a => Events(a) }
    }

    def reduceLeftTo[A, B](fa: Events[A])(f: A => B)(g: (B, A) => B) = {
      fa
        .values
        .reduceLeftTo(_.reduceLeftTo(f)(g))((b, as) => as.foldLeft(b)(g))
    }

    def reduceRightTo[A, B](fa: Events[A])(f: A => B)(g: (A, Eval[B]) => Eval[B]) = {
      fa
        .values
        .reduceRightTo(_.reduceRightTo(f)(g))((as, b) => b.map(b => as.foldRight(b)(g)))
        .flatten
    }

    def foldLeft[A, B](fa: Events[A], b: B)(f: (B, A) => B) = {
      fa
        .values
        .foldLeft(b) { (b, as) => as.foldLeft(b)(f) }
    }

    def foldRight[A, B](fa: Events[A], b: Eval[B])(f: (A, Eval[B]) => Eval[B]) = {
      fa
        .values
        .foldRight(b) { (as, b) => as.foldRight(b)(f) }
    }
  }


  implicit def orderEvents[A](implicit A: Order[A]): Order[Events[A]] = Order.by { _.values }


  implicit def show[A: Show]: Show[Events[A]] = {
    events =>
      val str = events.values match {
        case Nel(events, Nil) => events.mkString_(",")
        case events           => events.map { _.toList.mkString(",") }.mkString_("[", "],[", "]")
      }
      s"${ events.productPrefix }($str)"
  }


  implicit def eqEvents[A: Eq]: Eq[Events[A]] = Eq.by { _.values }


  /**
    * @return detached batches of single event each
    */
  def of[A](a: A, as: A*): Events[A] = attached(a, as: _*)

  def batched[A](a: Nel[A], as: Nel[A]*): Events[A] = Events(Nel(a, as.toList))

  /**
    * @return single batch of attached events
    */
  def attached[A](a: A, as: A*): Events[A] = Events(Nel.of(Nel(a, as.toList)))

  /**
    * @return detached batches of single event
    */
  def detached[A](a: A, as: A*): Events[A] = Events(Nel(a, as.toList).map { a => Nel.of(a) })
}
