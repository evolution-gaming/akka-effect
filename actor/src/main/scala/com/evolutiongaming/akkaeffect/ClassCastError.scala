package com.evolutiongaming.akkaeffect

import scala.reflect.ClassTag

object ClassCastError {

  def apply[A, B](a: A)(implicit tag: ClassTag[B]): Throwable =
    apply(a.getClass, tag.runtimeClass)

  def apply(actual: Class[_], expected: Class[_]): Throwable =
    new ClassCastException(s"${actual.getName} cannot be cast to ${expected.getName}")
}
