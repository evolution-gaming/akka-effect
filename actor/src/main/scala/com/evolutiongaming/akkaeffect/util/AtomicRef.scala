package com.evolutiongaming.akkaeffect.util

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

trait AtomicRef[A] {

  def get(): A

  def set(a: A): Unit

  def getAndSet(a: A): A

  def update(f: A => A): Unit

  def modify[B](f: A => (A, B)): B
}

object AtomicRef {

  def apply[A](value: A): AtomicRef[A] = {

    val ref = new AtomicReference(value)

    new AtomicRef[A] {

      def get() = ref.get()

      def set(a: A) = ref.set(a)

      def getAndSet(a: A) = ref.getAndSet(a)

      def update(f: A => A) = {
        @tailrec def update(): Unit = {
          val a  = ref.get()
          val a1 = f(a)
          if (!ref.compareAndSet(a, a1)) update()
        }

        update()
      }

      def modify[B](f: A => (A, B)) = {
        @tailrec def modify(): B = {
          val a       = ref.get()
          val (a1, b) = f(a)
          if (ref.compareAndSet(a, a1)) b
          else modify()
        }

        modify()
      }
    }
  }
}
