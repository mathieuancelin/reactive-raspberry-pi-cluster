package com.amazing.store.tools

import java.util.concurrent.atomic.AtomicReference

class Reference[T](name: String) {

  private val ref = new AtomicReference[Option[T]](None)

  // TODO : only settable once ???
  def <==(value: T): Option[T] = set(value)

  def set(value: T): Option[T] = ref.getAndSet(Option(value))

  def cleanup(): Option[T] = ref.getAndSet(None)

  def apply(): T = ref.get().getOrElse(throw new RuntimeException(s"Reference to $name was not properly initialized ..."))

  def get() = apply()

  def asOption() = ref.get()

  private def withValue(value: Option[T]): Reference[T] = {
    val newRef = Reference.empty[T]()
    newRef.ref.set(value)
    newRef
  }

  def ==>(f: (T) => Any): Reference[T] = combine(f)
  def mutate(f: (T) => Any): Reference[T] = combine(f)
  def chain(f: (T) => Any): Reference[T] = combine(f)
  def combine(f: (T) => Any): Reference[T] = {
    f(get())
    this
  }

  def isEmpty: Boolean = ref.get.isEmpty
  def isDefined: Boolean = ref.get.isDefined
  def getOrElse[B >: T](default: => B): B = ref.get.getOrElse(default)
  def map[B](f: (T) => B): Reference[B] = new Reference[B](name).withValue(ref.get.map(f))
  def fold[B](ifEmpty: => B)(f: (T) => B): B = ref.get.fold(ifEmpty)(f)
  def flatMap[B](f: (T) => Reference[B]): Reference[B] = new Reference[B](name).withValue(ref.get.flatMap(t => f(t).asOption()))
  def filter(p: (T) => Boolean): Reference[T] = new Reference[T](name).withValue(ref.get.filter(p))
  def filterNot(p: (T) => Boolean): Reference[T] = new Reference[T](name).withValue(ref.get.filterNot(p))
  def nonEmpty: Boolean = ref.get.nonEmpty
  def exists(p: (T) => Boolean): Boolean = ref.get.exists(p)
  def forall(p: (T) => Boolean): Boolean = ref.get.forall(p)
  def foreach[U](f: (T) => U): Unit = ref.get.foreach(f)
  def call[U](f: (T) => U): Unit = ref.get.foreach(f)
  def collect[B](pf: PartialFunction[T, B]): Reference[B] = new Reference[B](name).withValue(ref.get.collect(pf))
  def orElse[B >: T](alternative: => Reference[B]): Reference[B] = new Reference[B](name).withValue(ref.get.orElse(alternative.asOption()))
}

object Reference {
  def of[T](value: T): Reference[T]               = apply(value)
  def of[T](name: String, value: T): Reference[T] = apply(name, value)
  def apply[T](value: T): Reference[T]               = apply(IdGenerator.uuid, value)
  def apply[T](name: String, value: T): Reference[T] = {
    val ref = new Reference[T](name)
    ref.set(value)
    ref
  }
  def empty[T](name: String): Reference[T] = new Reference[T](name)
  def empty[T](): Reference[T] = empty(IdGenerator.uuid)
}