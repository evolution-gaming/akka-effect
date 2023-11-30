package com.evolutiongaming.akkaeffect.persistence

trait Event[E] {

  def event: E
  def seqNr: SeqNr

}

object Event {

  private case class Const[E](event: E, seqNr: SeqNr) extends Event[E]

  def const[E](event: E, seqNr: SeqNr): Event[E] = Const(event, seqNr)

}
