package com.evolutiongaming.akkaeffect

import cats.syntax.all._
import cats.{Functor, Monad}

final case class Call[F[_], A, B](msg: A, reply: Reply[F, B])

object Call {

  implicit def functorCall[F[_], B]: Functor[Call[F, *, B]] = new Functor[Call[F, *, B]] {
    def map[A, A1](fa: Call[F, A, B])(f: A => A1): Call[F, A1, B] = fa.copy(msg = f(fa.msg))
  }


  implicit class CallOps[F[_], A, B](val self: Call[F, A, B]) extends AnyVal {

    def convert[A1, B1](
      af: A => F[A1],
      bf: B1 => F[B])(implicit
      F: Monad[F]
    ): F[Call[F, A1, B1]] = {
      af(self.msg).map { a => Call(a, self.reply.convert(bf)) }
    }
  }
}