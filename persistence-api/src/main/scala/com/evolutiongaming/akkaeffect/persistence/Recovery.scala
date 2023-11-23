package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream.Stream

/**
  * Representation of __started__ recovery process:
  * snapshot is already loaded in memory (if any)
  * while events will be loaded only on materialisation of [[Stream]]
  * @tparam F effect
  * @tparam S snapshot
  * @tparam E event
  */
trait Recovery[F[_], S, E] {

  def snapshot: Option[Snapshot[S]]
  def events: Stream[F, Event[E]]

}

object Recovery {

  def const[F[_], S, E](_snapshot: Option[Snapshot[S]],
                        _events: Stream[F, Event[E]]): Recovery[F, S, E] =
    new Recovery[F, S, E] {

      override def snapshot: Option[Snapshot[S]] = _snapshot

      override def events: Stream[F, Event[E]] = _events

    }

}
