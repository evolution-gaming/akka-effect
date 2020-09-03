package com.evolutiongaming.akkaeffect.persistence
import cats.{Order, Show}
import pureconfig.ConfigReader


final case class TypeName(value: String) {

  override def toString: String = value
}

object TypeName {

  implicit val orderTypeName: Order[TypeName] = Order.by { a: TypeName => a.value }

  implicit val showTypeName: Show[TypeName] = Show.fromToString

  implicit val configReaderTypeName: ConfigReader[TypeName] = ConfigReader[String].map { a => TypeName(a) }
}