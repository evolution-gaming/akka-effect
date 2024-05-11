package com.evolutiongaming.akkaeffect

package object persistence {

  type SeqNr = Long

  object SeqNr {

    val Min: SeqNr = 0L

    val Max: SeqNr = Long.MaxValue
  }

  type Timestamp = Long

}
