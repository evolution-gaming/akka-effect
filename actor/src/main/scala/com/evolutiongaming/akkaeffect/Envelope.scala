package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.Functor
import cats.syntax.all._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.MonadThrowable

import scala.reflect.ClassTag

final case class Envelope[+A](msg: A, from: ActorRef)

object Envelope {

  implicit val functorEnvelope: Functor[Envelope] = new Functor[Envelope] {
    def map[A, B](fa: Envelope[A])(f: A => B): Envelope[B] = fa.copy(msg = f(fa.msg))
  }

  implicit class EnvelopeOps[A](val self: Envelope[A]) extends AnyVal {

    def cast[F[_]: MonadThrowable, B <: A: ClassTag]: F[Envelope[B]] =
      self.msg
        .castM[F, B]
        .map(a => self.copy(msg = a))
  }
}
