package com.evolutiongaming.akkaeffect.persistence

trait Event[E] {

  def event: E
  def seqNr: SeqNr

}

object Event {

  def const[E](e: E, nr: SeqNr): Event[E] = new Event[E] {
    override def event: E = e
    override def seqNr: SeqNr = nr
  }

}
