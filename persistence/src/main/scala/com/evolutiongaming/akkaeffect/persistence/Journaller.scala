package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import com.evolutiongaming.akkaeffect.Fail
import com.evolutiongaming.catshelper.MonadThrowable


trait Journaller[F[_], -A] {
  /**
    * @see [[akka.persistence.PersistentActor.persistAllAsync]]
    */
  def append: Append[F, A]

  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo: DeleteEventsTo[F]
}


object Journaller {

  def apply[F[_], A](
    append: Append[F, A],
    deleteEventsTo: DeleteEventsTo[F]
  ): Journaller[F, A] = {

    val append1 = append

    new Journaller[F, A] {

      def append = append1

      def deleteTo = deleteEventsTo
    }
  }


  implicit class JournallerOps[F[_], A](val self: Journaller[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: Monad[F]): Journaller[F, B] = new Journaller[F, B] {

      val append = self.append.convert(f)

      def deleteTo = self.deleteTo
    }


    def narrow[B <: A]: Journaller[F, B] = new Journaller[F, B] {

      val append = self.append.narrow[B]

      def deleteTo = self.deleteTo
    }


    def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Journaller[F, A] = new Journaller[F, A] {

      val append = self.append.withFail(fail)

      val deleteTo = self.deleteTo.withFail(fail)
    }
  }
}