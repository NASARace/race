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

package gov.nasa.race

import java.util

import com.github.nscala_time.time.Imports._
import com.typesafe.config.{ Config, ConfigValue }

import scala.annotation.tailrec
import scala.collection.immutable.{ Map => ImmutableMap, Set => ImmutableSet }
import scala.collection.mutable.{ Map => MutableMap, Set => MutableSet }
import scala.util.control.Breaks
import scala.language.implicitConversions

/**
 * general utility methods
 */
package object common {

  //--- Option utilities

  def ifSome[T](opt: Option[T])(f: (T) => Any): Option[T] = {
    if (opt.isDefined) f(opt.get)
    opt
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

  /** execute two functions and return the result of the first one */
  def withSubsequent[T] (f: =>T)(g: =>Any): T = {
    val res = f
    g
    res
  }

  /** return the result of the first function, after executing the second function with the result as argument */
  def withDo[A](f: =>A)(g: A=>Any): A = {
    val res = f
    g(res)
    res
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

  //--- nullables

  def ifNotNull[T](t: T)(f: (T) => Any): T = {
    if (t != null) f(t)
    t
  }

  def ifNull[T](t: T)(f: => T) = if (t == null) f else t
  def nonNullOrElse[T](t: T, f: => T) = if (t != null) t else f // slightly different version with 'orElse' syntax
  def ifNullOrEmpty(s: String)(f: => Any) = if (s == null || s.isEmpty) f

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

  def forever (f: =>Unit): Unit = {
    while(true) f
  }

  /** loop forever unless more than `maxException` consecutive exceptions occur */
  def loopWithExceptionLimit (maxExceptions: Int=5)(f: =>Unit): Unit = {
    var terminate = false
    var exceptionCount = 0

    while (!terminate) {
      try {
        while(true) f
        exceptionCount = 0
      } catch {
        case t: Throwable =>
          exceptionCount += 1
          if (exceptionCount > maxExceptions) terminate = true
      }
    }
  }


  @inline def ifTrue(cond: Boolean)(f: => Any) = if (cond) { f; true } else false
  @inline def ifFalse(cond: Boolean)(f: => Any) = if (cond) true else { f; false }

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

  def containsAny[T](a: Set[T], b: Traversable[T]): Boolean = {
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

  /** are d1 and d2 on different sides of threshold */
  @inline def crossedThreshold(d1: Double, d2: Double, threshold: Double): Boolean =
    Math.signum(d1 - threshold) != Math.signum(d2 - threshold)

  class Threshold (val threshold: Double, crossedBelow: => Unit, crossedAbove: => Unit) extends Ordered[Threshold]{
    def this(threshold: Double, crossedAction: => Unit) = this(threshold, crossedAction, crossedAction)

    def checkCrossed (dOld: Double, dNew: Double): Boolean = {
      if (crossedThreshold(dOld,dNew,threshold)) {
        if (dOld > dNew) crossedBelow else crossedAbove
        true
      } else false
    }

    def compare (other: Threshold) = if (threshold < other.threshold) -1 else if (threshold > other.threshold) 1 else 0
  }

  @tailrec def repeat(nTimes: Int)(f: => Any): Unit = {
    if (nTimes > 0) {
      f
      repeat(nTimes - 1)(f)
    }
  }

  def clear(a: Array[Char]) = util.Arrays.fill(a, 0.toChar)

  /** helper to debug for-comprehensions */
  def checkComprehension (msg: String): Option[String] = {
    println(msg)
    Some(msg)
  }

  //--- system properties
  val userHome = System.getProperty("user.home")
  val userDir = System.getProperty("user.dir")
  val userName = System.getProperty("user.name")

  //--- common functional and feature types

  // something that has a 'date' field
  trait Dated {
    val date: DateTime
  }

  // something that has a 'config' object with a 'name' entry
  trait NamedConfigurable {
    val config: Config
    // we can't use ConfigConversions, this is toplevel
    val name = try { config.getString("name") } catch { case _: Throwable => getClass.getName }
  }

  // something that can be filtered
  trait Filter[T] {
    def pass(x: T): Boolean
  }
  trait ConfigurableFilter extends Filter[Any] with NamedConfigurable

  // something that can be translated
  trait Translator[S, T] {
    def translate(src: S): Option[T]
  }
  trait ConfigurableTranslator extends Translator[Any, Any] with NamedConfigurable

  trait TimeTranslator[T] {
    def translate(obj: T, simTime: DateTime): T
  }
  trait ConfigurableTimeTranslator extends TimeTranslator[Any] with NamedConfigurable

  trait ConfigValueMapper extends Translator[Any, ConfigValue] with NamedConfigurable

  def normalizedDegrees(deg: Double) = if (deg < 0) 360.0 + deg else deg

  //--- Java interoperability

  // for order-independent processing of XmlPullParser attributes
  trait XmlAttrProcessor {
    this: XmlPullParser =>

    def processAttributes(pf: PartialFunction[String,Unit]) = {
      while (parseNextAttribute) pf(attr)
    }
  }

  // to use Scala lambdas as Java method arguments
  implicit def sfunc1Tojfunc1[A](f: (A) => Any) = new java.util.function.Function[A, Any]() {
    override def apply(a: A): Any = f(a)
  }

  implicit def sFunc2TojBiFunc[A, B, R](f: (A, B) => R): java.util.function.BiFunction[A, B, R] = new java.util.function.BiFunction[A, B, R]() {
    override def apply(a: A, b: B): R = f(a, b)
  }

}