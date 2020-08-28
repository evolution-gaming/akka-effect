package com.evolutiongaming.akkaeffect

import akka.persistence.Recovery
import cats.Show

package object persistence {

  type SeqNr = Long

  object SeqNr {

    val Min: SeqNr = 0L

    val Max: SeqNr = Long.MaxValue
  }


  type Timestamp = Long


  implicit val showRecovery: Show[Recovery] = Show.fromToString
}