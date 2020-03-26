package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Order, Show}


final case class TypeName(value: String) {

  override def toString: String = value
}

object TypeName {

  implicit val orderTypeName: Order[TypeName] = Order.by { a: TypeName => a.value }

  implicit val showTypeName: Show[TypeName] = Show.fromToString
}