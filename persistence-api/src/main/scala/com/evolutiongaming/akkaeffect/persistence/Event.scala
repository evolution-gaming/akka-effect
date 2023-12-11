package com.evolutiongaming.akkaeffect.persistence

trait Event[E] {

  def event: E
  def seqNr: SeqNr

}

object Event {

  def const[E](event: E, seqNr: SeqNr): Event[E] = {

    case class Const(event: E, seqNr: SeqNr) extends Event[E]

    Const(event, seqNr)
  }

}
