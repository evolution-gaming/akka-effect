package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import com.evolutiongaming.akkaeffect.persistence.SeqNr

import scala.util.Try

sealed trait CmdResult[F[_]]

object CmdResult {

  def empty[F[_]]: CmdResult[F] = ???

  final case class Change[F[_], S, E](
    state: S,
    events: Nel[Nel[E]],
    callback: Try[SeqNr] => F[Unit])
}
