package com.evolution.akkaeffect.eventsopircing.persistence

trait Event[E] {

  def event: E
  def seqNr: SeqNr

}
