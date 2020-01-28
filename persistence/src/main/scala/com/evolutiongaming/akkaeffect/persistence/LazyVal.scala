package com.evolutiongaming.akkaeffect.persistence


import cats.implicits._

/**
  * is not synchronised, safe to use within actor
  */
object LazyVal {

  def apply[A](f: => A): () => A = {
    var a = none[A]
    () =>
      a.getOrElse {
        val b = f
        a = b.some
        b
      }
  }
}
