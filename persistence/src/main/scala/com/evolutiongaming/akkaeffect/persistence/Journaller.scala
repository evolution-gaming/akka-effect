package com.evolutiongaming.akkaeffect.persistence

import cats.{Applicative, FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.Fail
import com.evolutiongaming.catshelper.{Log, MeasureDuration, MonadThrowable}
import com.evolutiongaming.smetrics


/**
  * Describes communication with underlying journal
  *
  * @tparam A event
  */
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

  def empty[F[_]: Applicative, A]: Journaller[F, A] = {
    Journaller(Append.empty[F, A], DeleteEventsTo.empty[F])
  }


  def apply[F[_], A](
    append: Append[F, A],
    deleteEventsTo: DeleteEventsTo[F]
  ): Journaller[F, A] = {

    val append1 = append

    class Main
    new Main with Journaller[F, A] {

      def append = append1

      def deleteTo = deleteEventsTo
    }
  }


  private sealed abstract class Narrow

  private sealed abstract class Convert

  private sealed abstract class MapK

  private sealed abstract class WithFail


  implicit class JournallerOps[F[_], A](val self: Journaller[F, A]) extends AnyVal {

    def mapK[G[_]: Applicative](f: F ~> G): Journaller[G, A] = {
      new MapK with Journaller[G, A] {

        def append = self.append.mapK(f)

        def deleteTo = self.deleteTo.mapK(f)
      }
    }


    def convert[B](f: B => F[A])(implicit F: Monad[F]): Journaller[F, B] = {
      new Convert with Journaller[F, B] {

        val append = self.append.convert(f)

        def deleteTo = self.deleteTo
      }
    }


    def narrow[B <: A]: Journaller[F, B] = {
      new Narrow with Journaller[F, B] {

        val append = self.append.narrow[B]

        def deleteTo = self.deleteTo
      }
    }

    @deprecated("Use `withLogging1` instead", "0.4.0")
    def withLogging(
      log: Log[F])(implicit
      F: FlatMap[F],
      measureDuration: smetrics.MeasureDuration[F]
    ): Journaller[F, A] = {
      withLogging1(log)(F, measureDuration.toCatsHelper)
    }

    def withLogging1(
      log: Log[F])(implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F]
    ): Journaller[F, A] = {
      Journaller(
        self.append.withLogging1(log),
        self.deleteTo.withLogging1(log))
    }


    def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Journaller[F, A] = {
      new WithFail with Journaller[F, A] {

        val append = self.append.withFail(fail)

        val deleteTo = self.deleteTo.withFail(fail)
      }
    }
  }
}