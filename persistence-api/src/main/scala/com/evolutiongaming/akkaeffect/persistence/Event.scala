package com.evolutiongaming.akkaeffect.persistence

trait Event[E] {

  def event: E
  def seqNr: SeqNr

}
