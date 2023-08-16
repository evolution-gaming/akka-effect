package com.evolutiongaming.akkaeffect.eventsourcing

import cats.syntax.all._
import cats.{Applicative, FlatMap, Functor, ~>}
import com.evolutiongaming.akkaeffect.persistence
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.catshelper.{Log, MeasureDuration}

trait Journaller[F[_]] {
  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}

object Journaller {

  def empty[F[_]: Applicative]: Journaller[F] = const(().pure[F].pure[F])


  def const[F[_]](value: F[F[Unit]]): Journaller[F] = (_: SeqNr) => value


  def apply[F[_], A](journaller: persistence.Journaller[F, A]): Journaller[F] = {
    (seqNr: SeqNr) => journaller.deleteTo(seqNr)
  }


  implicit class JournallerOps[F[_]](val self: Journaller[F]) extends AnyVal {

    def mapK[G[_]: Functor](f: F ~> G): Journaller[G] = {
      (seqNr: SeqNr) => f(self.deleteTo(seqNr)).map(a => f(a))
    }

    def withLogging1(
      log: Log[F])(implicit
      F: FlatMap[F],
      measureDuration: MeasureDuration[F]
    ): Journaller[F] = (seqNr: SeqNr) => {
      for {
        d <- MeasureDuration[F].start
        r <- self.deleteTo(seqNr)
      } yield for {
        r <- r
        d <- d
        _ <- log.info(s"delete events to $seqNr in ${ d.toMillis }ms")
      } yield r
    }
  }
}