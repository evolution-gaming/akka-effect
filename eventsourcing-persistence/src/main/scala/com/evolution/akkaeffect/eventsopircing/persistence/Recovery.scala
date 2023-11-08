package com.evolution.akkaeffect.eventsopircing.persistence

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
