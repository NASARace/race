/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa

import gov.nasa.race.uom.DateTime

import scala.annotation.tailrec
import scala.collection.immutable.{Map => ImmutableMap, Set => ImmutableSet}
import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.control.Breaks

/**
  * general definitions used across all of RACE
  *
  * This mostly contains methods that are control abstractions, with the intent to make
  * code more readable and compact by avoiding duplication
  */
package object race {

  //--- Option utilities

  def ifSome[T](opt: Option[T])(f: (T) => Any): Option[T] = {
    if (opt.isDefined) f(opt.get)
    opt
  }

  def ifSomeOf[T](opts: Option[T]*)(f: (T) => Any): Option[T] = {
    opts foreach( _ match {
      case o@Some(t) => f(t); return o
      case None =>
    })
    None
  }

  def ifTrueSome[A] (cond: Boolean)(f: => A): Option[A] = {
    if (cond) Some(f) else None
  }

  def flatMapAny[T: Manifest,U](opt: Option[Any])(f: T=>Option[U]): Option[U] = {
    opt match {
      case Some(t: T) => f(t)
      case _ => None
    }
  }

  def none[T](f: =>Any): Option[T] = { // can be used as alternative for ifSome{..}
    f
    None
  }
  def none[T](msg: String): Option[T] = {
    println(msg)
    None
  }

  def withSomeOrElse[T,R](opt: Option[T], defaultValue: R)(f: (T) => R): R = {
    if (opt.isDefined) f(opt.get) else defaultValue
  }

  def ifNone[T](opt: Option[T])(f: => Unit): Option[T] = {
    opt match {
      case None => f; None
      case some => some
    }
  }

  def trySome[T](f: => T): Option[T] = {
    try {
      Some(f)
    } catch {
      case _: Throwable => None
    }
  }

  def tryWithSome[T, S](opt: Option[T])(f: (T) => S): Option[S] = {
    if (opt.isDefined) {
      try {
        val s = f(opt.get)
        Some(s)
      } catch {
        case _: Throwable => None
      }
    } else None
  }

  def tryFinally[A](f: =>Option[A])(g: =>Unit): Option[A] = {
    try {
      f
    } catch {
      case _: Throwable => None
    } finally {
      g
    }
  }

  def tryNull[A <:AnyRef](f: => A): A = {
    try {
      f
    } catch {
      case _: Throwable => null.asInstanceOf[A]
    }
  }

  def tryWithResource[T,R <:AutoCloseable] (r: R)(f: (R)=>T): T = {
    try {
      f(r)
    } finally {
      if (r != null) r.close
    }
  }

  /** execute two functions and return the result of the first one */
  def withSubsequent[T] (f: =>T)(g: =>Any): T = {
    val res = f
    g
    res
  }

  /** set new field value and return previous one */
  def updateField[T](newValue: T, get: =>T, set: (T)=>Unit): T = {
    val oldValue = get
    set(newValue)
    oldValue
  }

  /** return object after calling an initialize functions on it */
  def yieldInitialized[A](a: A)(init: A=>Any): A = {
    init(a)
    a
  }

  /** return first parameter after executing f */
  def yieldAfter[A](a:A)(f: =>Unit): A = {
    f
    a
  }

  def lift[A, B](f: A => B): Option[A] => Option[B] = _ map f

  def liftPredicate[A](o1: Option[A], o2: Option[A])(f: (A, A) => Boolean): Boolean = {
    o1.nonEmpty && o2.nonEmpty && f(o1.get, o2.get)
  }

  def lift2[A, B, C](oa: Option[A], ob: Option[B])(f: (A, B) => C): Option[C] = {
    for (a <- oa; b <- ob) yield f(a, b)
  }

  def flatLift2[A, B, C](oa: Option[A], ob: Option[B])(f: (A, B) => Option[C]): Option[C] = {
    for (a <- oa; b <- ob) yield f(a, b).get
  }

  def firstFlatMapped[A,B](seq: Seq[A])(f: (A)=>Option[B]): Option[B] = {
    seq.foreach { a =>
      val ob = f(a)
      if (ob.isDefined) return ob // shortcut
    }
    None
  }

  //--- nullables

  def ifNotNull[T](t: T)(f: (T) => Any): T = {
    if (t != null) f(t)
    t
  }

  def ifNull[T](t: T)(f: => T) = if (t == null) f else t
  def nonNullOrElse[T](t: T, f: => T) = if (t != null) t else f // slightly different version with 'orElse' syntax
  def ifNullOrEmpty(s: String)(f: => Any) = if (s == null || s.isEmpty) f

  def optional[T <: AnyRef] (v: T): Option[T] = if (v != null) Some(v) else None

  /** loop while a condition expression does not return null */
  def whileNotNull[T <: AnyRef](next: => T)(p: T => Any): Unit = {
    var o = next
    while (o != null) {
      p(o)
      o = next
    }
  }

  /** breakable while(!null) loop without return value */
  def whileBreakableNotNull[T <: AnyRef](next: => T)(p: (T, Breaks) => Any): Unit = {
    val b = new Breaks()
    b.breakable {
      var o = next
      while (o != null) {
        p(o, b)
        o = next
      }
    }
  }

  /** breakable while(!null) loop with return value */
  // this is suspiciously close to a fold, but supports breaks
  def forBreakableNotNull[T <: AnyRef, R](init: R, next: => T)(p: (T, R, Breaks) => R): R = {
    val b = new Breaks()
    var res = init
    b.breakable {
      var o = next
      while (o != null) {
        res = p(o, res, b)
        o = next
      }
    }
    res
  }

  def whileSome[T](opt: =>Option[T])(f: T=>Unit): Unit = {
    var o = opt
    while (o.isDefined) {
      f(o.get)
      o = opt
    }
  }

  def forever (f: =>Unit): Unit = {
    while(true) f
  }

  /** loop forever unless more than `maxException` consecutive exceptions occur */
  def loopForeverWithExceptionLimit(maxFailure: Int=5)(f: =>Unit): Boolean = {
    var failures = 0

    while (true) {
      try {
        f
        failures = 0
      } catch {
        case _: Throwable =>
          failures += 1
          if (failures > maxFailure) return false
      }
    }
    true
  }

  def loopWithExceptionLimit (maxFailure: Int)(cond: =>Boolean)(f: =>Unit): Boolean = {
    var failures = 0

    while (cond) {
      try {
        f
        failures = 0 // reset after successful cycle
      } catch {
        case _: Throwable =>
          failures += 1
          if (failures >= maxFailure) return false
      }
    }
    true
  }

  /** loop with index over all elements returned by an iterator */
  def forIterator[T] (it: Iterator[T]) (f: (T,Int)=>Unit): Unit = {
    @tailrec def _nextCycle (i: Int): Unit = {
      f(it.next(),i)
      if (it.hasNext) _nextCycle(i+1)
    }
    if (it.hasNext) _nextCycle(0)
  }

  @inline def ifTrue(cond: Boolean)(f: => Unit): Boolean = if (cond) { f; true } else false
  @inline def ifFalse(cond: Boolean)(f: => Unit): Boolean = if (cond) true else { f; false }

  @inline def ifTrueCheck(cond: Boolean)(f: => Boolean): Boolean = if (cond) f else false
  @inline def ifFalseCheck(cond: Boolean)(f: => Boolean): Boolean = if (!cond) f else true

  def toIntOrElse(s: String, f: => Int): Int = if (s == null) f else s.toInt // we pass parse errors up

  def matchEach[A](l: Seq[A])(pf: PartialFunction[A, Any]) = l.foreach(e => pf(e))

  def ifInstanceOf[T: Manifest](o: Any)(f: T => Any) = {
    if (manifest[T].runtimeClass.isInstance(o)) f(o.asInstanceOf[T])
  }

  def asInstanceOf[T: Manifest](o: Any): Option[T] = {
    if (manifest[T].runtimeClass.isInstance(o)) Some(o.asInstanceOf[T]) else None
  }

  def asInstanceOfMatching[T: Manifest](o: Any, pred: (T)=>Boolean): Option[T] = {
    asInstanceOf[T](o).flatMap( t => if (pred(t)) Some(t) else None)
  }

  def containsAny[T](a: Set[T], b: Iterable[T]): Boolean = {
    b.foreach { e =>
      if (a.contains(e)) return true
    }
    false
  }
  def addIfAbsent[T](set: ImmutableSet[T], v: T)(f: => Any): ImmutableSet[T] = {
    if (!set.contains(v)) {
      f
      set + v
    } else set
  }
  def addIfAbsent[T](set: MutableSet[T], v: T)(f: => Any): MutableSet[T] = {
    if (!set.contains(v)) {
      f
      set += v
    } else set
  }
  def addIfAbsent[K, V](map: ImmutableMap[K, V], k: K)(f: => V): ImmutableMap[K, V] = {
    if (!map.contains(k)) {
      map + (k -> f)
    } else map
  }
  def addIfAbsent[K, V](map: MutableMap[K, V], k: K)(f: => V): MutableMap[K, V] = {
    if (!map.contains(k)) {
      map += (k -> f)
    } else map
  }

  @tailrec def repeat(nTimes: Int)(f: => Unit): Unit = {
    if (nTimes > 0) {
      f
      repeat(nTimes - 1)(f)
    }
  }

  @tailrec def repeatUpTo(nTimes: Int)(cond: => Boolean)(f: => Unit): Boolean = {
    if (nTimes == 0) {
      false
    } else {
      if (cond) {
        f
        repeatUpTo(nTimes-1)(cond)(f)
      } else true
    }
  }

  @tailrec def loopFromTo(i: Int, i1: Int) (f: Int=>Unit): Unit = {
    if (i < i1) {
      f(i)
      loopFromTo(i+1,i1)(f)
    }
  }

  def clear(a: Array[Char]) = java.util.Arrays.fill(a, 0.toChar)

  def getNonEmptySeqOrElse[T] (s: Seq[T])(f: => Seq[T]): Seq[T] = if (s.nonEmpty) s else f
  def nonEmptyArrayOrElse[T] (a: Array[T])(f: => Array[T]): Array[T] = if (a.nonEmpty) a else f

  def mapToArray[T,U:ClassTag](s:Seq[T])(f: T=>U): Array[U] = {
    val a = new Array[U](s.size)
    var i=0
    s foreach { e => a(i) = f(e); i += 1}
    a
  }

  def mapIteratorToArray[T,U:ClassTag](it: Iterator[T], len: Int)(f: T=>U): Array[U] = {
    val a = new Array[U](len)
    var i=0
    it foreach { e => a(i) = f(e); i += 1 }
    a
  }

  def foreachCorrespondingIndex[T] (a: Array[T], b: Array[T])(f: (Int)=>Unit) = {
    var i = 0
    while (i < a.length && i < b.length) {
      f(i)
      i += 1
    }
  }

  def findLastIn[T] (ts: Array[T])(p: (T)=>Boolean): Option[T] = {
    @tailrec def _findReverse (i: Int): Option[T] = {
      if (i >= 0){
        val t = ts(i)
        if (p(t)) Some(t) else _findReverse(i-1)
      } else None
    }
    _findReverse(ts.length-1)
  }
  def findFirstIn[T] (ts: Array[T])(p: (T)=>Boolean): Option[T] = ts.find(p) // just to keep it consistent

  def findLastIn[T] (ts: Seq[T])(p: (T)=>Boolean): Option[T] = {
    for (t <- ts.reverseIterator) if (p(t)) return Some(t)
    None
  }
  def findFirstIn[T] (ts: Seq[T])(p: (T)=>Boolean): Option[T] = ts.find(p) // just to keep it consistent

  /** helper to debug for-comprehensions */
  def checkComprehension (msg: String): Option[String] = {
    println(msg)
    Some(msg)
  }

  def applyTo[T](t: T)(f: (T)=>Unit) = {
    f(t)
    t
  }

  def getHomogeneous[T,R](items:Array[T], f: (T)=>R, comp: (R,R)=>Boolean): Option[R] = {
    if (items.isEmpty) return None

    val z = f(items.head)
    if (items.exists( i => !comp(z,f(i)))) None else Some(z)
  }

  //--- system properties
  val userHome = System.getProperty("user.home")
  val userDir = System.getProperty("user.dir")
  val userName = System.getProperty("user.name")

  //--- common functional and feature types

  // something that has a date
  trait Dated  {
    def date: DateTime
  }

  implicit object DatedOrdering extends Ordering[Dated] {
    def compare (d1: Dated, d2: Dated): Int = d1.date.compare(d2.date)
  }

  /**
    * something that can be identified by ids (channel- and global-)
    */
  trait IdentifiableObject {
    def id: String // channel specific (track number, tail number etc.)
    def cs: String // call sign (cross-channel id)
    def gid: String = cs // cross channel (global) id
  }

  /**
    * an IdentifiableObject that has a role in a given org, both given as numeric codes
    */
  trait OrgObject extends IdentifiableObject {
    def role: Int
    def org: Int
  }

  // something that can translate between two types
  trait Translator[S, T] {
    def translate(src: S): Option[T]
  }

  // something that can translate time values in a given type
  trait TimeTranslator[T] {
    def translate(obj: T, simTime: DateTime): T
  }

  // something that can be filtered
  trait Filter[T] {
    def pass(x: T): Boolean
  }
  class PassAllFilter[T] extends Filter[T] {
    def pass (x: T): Boolean = true
  }
  class RejectAllFilter[T] extends Filter[T] {
    def pass (x: T): Boolean = false
  }

  // something that can be converted to a String
  // (Object.toString is always defined, so we can't easily check if there is a more specialized converter)
  trait Show {
    def show: String
  }

  // this is a default show implementation for non-Show types we can't directly extend
  implicit class Showable (o: Object) extends Show {
    override def show = o.toString
  }

  trait SchemaImplementor {
    val schema: String
    def compliesWith (s: String):Boolean = schema == s
  }

  /**
    * universal trait to support undefined values
    *
    * NOTE - this can be used for value classes but they have to override *all* methods
    * in order to avoid allocation when calling the default methods, not just the abstract ones.
    *
    * However, at least as of Scala 2.13 it appears that just extending a universal trait is NOT
    * automatically causing allocation anymore
    */
  trait MaybeUndefined extends Any {
    def isDefined: Boolean

    // NOTE - calling this without override causes allocation
    def isUndefined: Boolean  = !isDefined
  }


  /**
    * a computation with a generic success value and a common failure type, which is just a failure message.
    * This is a specialized and more intuitive Either[String,+T]
    */
  sealed trait ResultValue[+T] {
    //--- the abstract data model (avoid allocation for the purpose of testing results)
    def failed: Boolean
    def failure: Failure
    def get: T

    //--- specialized interface
    def succeeded: Boolean = !failed

    def ifSuccess (f: =>Unit): Unit = if (!failed) f
    def ifSuccess (f: T=>Unit): Unit = if (!failed) f(get)

    def ifFailed (f: =>Unit): Unit = if (failed) f
    def ifFailed (f: String=>Unit): Unit = if (failed) f(failure.msg)  // use this also to turn failure into exceptions

    //--- standard functional interface
    def foreach (f: T=>Unit): Unit = if (!failed) f(get)
    def map[U] (f: T=>U): ResultValue[U] = if (!failed) SuccessValue(f(get)) else failure
    def flatMap[U] (f: T=>ResultValue[U]): ResultValue[U] = if (!failed) f(get) else failure
    // isEmpty, nonEmpty would be a bit non-intuitive with Result

    def asOption: Option[T]
  }

  /**
    * value carrying success instances
    */
  case class SuccessValue[+T] (value: T) extends ResultValue[T] {
    def failed: Boolean = false
    def failure: Failure = throw new NoSuchElementException("result did succeed")
    def get: T = value
    def asOption: Option[T] = Some(value)
  }


  /**
    * a ResultValue without a value - just indicates if a computation did succeed
    * This is a specialized and more intuitive Option[String]
    */
  sealed trait Result {
    def failed: Boolean
    def ifSuccess (f: =>Unit): Unit = if (!failed) f
    def ifFailed (f: =>Unit): Unit = if (failed) f
    def ifFailed (f: String=>Unit): Unit
  }

  /**
    * singleton for successful Results without value. Just indicates a computation did succeed
    */
  object Success extends Result {
    def failed: Boolean = false
    def failure: Failure = throw new NoSuchElementException("result did succeed")
    def get: Nothing = throw new NoSuchElementException("result has no value")
    def ifFailed (f: String=>Unit): Unit = {}
  }

  /**
    * common failure type shared by all Results. Just holds an error message
    */
  case class Failure (msg: String) extends Result with ResultValue[Nothing] {
    def failed: Boolean = true
    def failure: Failure = this
    def get: Nothing = throw new NoSuchElementException("result did fail")
    override def ifSuccess (f: =>Unit): Unit = {}
    override def ifFailed (f: =>Unit): Unit = f
    override def ifFailed (f: String=>Unit): Unit = f(msg)

    def asOption: Option[Nothing] = None
  }
}