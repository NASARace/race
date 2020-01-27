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

import gov.nasa.race.common.inlined.ByteSlice

import scala.annotation.tailrec
import scala.collection.Seq
import scala.language.implicitConversions
import scala.math._
import scala.util.matching.Regex

/**
  * package gov.nasa.race.common holds common RACE specific types that can be instantiated from
  * dependent projects, especially from RaceActors.
  *
  * Types in this package are loosely coupled, their common denominator is just that they are RACE specific
  * utility constructs
  */
package object common {

  //--- numeric helpers and convenience methods

  final val Epsilon = 1.0e-10

  // all functions here have to start with lower case so that we can use upper case
  // in value classes such as .uom.Length (overriding does not work there because of type erasure)
  @inline def squared (d: Double) = d*d
  @inline def cubed (d: Double) = d*d*d
  @inline def sin2 (d: Double) = {
    val x = sin(d)
    x*x
  }

  // unfortunately we can't def √ for both Double and uom (AnyVal) arguments without ambiguity

  implicit class RichDouble (val d: Double) extends AnyVal {
    @inline def ** (e: Double) = Math.pow(d,e)
    @inline def `²` = d*d  // sub/superscript chars are not valid identifiers so we have to quote
    @inline def `³` = d*d*d

    @inline def within (x: Double, tolerance: Double) = Math.abs(d - x.d) <= tolerance
    @inline def ≈ (x: Double)(implicit Epsilon: Double) = Math.abs(d - x.d) <= Epsilon.d
    @inline def ~= (x: Double)(implicit Epsilon: Double) = Math.abs(d - x.d) <= Epsilon.d

    // TODO - how can we catch erroneous use of ==
    @inline def =:= (x: Double) = d == x.d
    @inline def ≡ (x: Double) = d == x.d

    @inline def isDefined = d != Double.NaN
    @inline def isUndefined = d == Double.NaN

    def round_1 = Math.round(d * 10.0) / 10.0
    def round_2 = Math.round(d * 100.0) / 100.0
    def round_3 = Math.round(d * 1000.0) / 1000.0
    def round_4 = Math.round(d * 10000.0) / 10000.0
    def round_5 = Math.round(d * 100000.0) / 100000.0
  }

  final val UndefinedDouble = Double.NaN
  @inline def isDefined (d: Double) = d != Double.NaN

  def Null[T <: AnyRef]: T = null.asInstanceOf[T]
  
  trait Counter {
    val counterThreshold: Int
    protected var count = 0

    def incCounter: Boolean = {
      count += 1
      if (count >= counterThreshold){
        count = 0
        false
      } else true
    }
  }

  def pow10 (e: Int): Long = {
    @tailrec def p (e: Int, acc: Long): Long = if (e == 0) acc else p(e-1, acc*10)
    p(e,1L)
  }

  @inline def minl(a: Long, b: Long): Long = Math.min(a,b)
  @inline def minl(a: Long, b: Long, c: Long): Long = Math.min( Math.min(a,b), c)
  @inline def minl(a: Long, b: Long, c: Long, d: Long): Long = Math.min( Math.min( Math.min(a,b),c), d)

  @inline def mind(a: Double, b: Double): Double = Math.min(a,b)
  @inline def mind(a: Double, b: Double, c: Double): Double = Math.min( Math.min(a,b), c)
  @inline def mind(a: Double, b: Double, c: Double, d: Long): Double = Math.min( Math.min( Math.min(a,b),c), d)

  @inline def maxl(a: Long, b: Long): Long = Math.max(a,b)
  @inline def maxl(a: Long, b: Long, c: Long): Long = Math.max( Math.max(a,b), c)
  @inline def maxl(a: Long, b: Long, c: Long, d: Long): Long = Math.max( Math.max( Math.max(a,b), c), d)

  @inline def maxd(a: Double, b: Double): Double = Math.max(a,b)
  @inline def maxd(a: Double, b: Double, c: Double): Double = Math.max( Math.max(a,b), c)
  @inline def maxd(a: Double, b: Double, c: Double, d: Long): Double = Math.max( Math.max( Math.max(a,b), c), d)

  @inline def constrainTo (d: Double, dMin: Double, dMax: Double): Double = {
    if (d < dMin) dMin
    else if (d > dMax) dMax
    else d
  }

  @inline def ifWithin (d: Double, dMin: Double, dMax: Double)(f: =>Unit) = {
    if (!(d < dMin || d > dMax)) f
  }

  // something that can be turned into XML
  trait XmlSource {
    def toXML: xml.Node
  }

  // a wrapper for generic sources (abbreviated because there are already a gazillion of 'Source' types in other packages)
  case class Src[S] (src: S)


  def measureNanos(nRounds: Int=1)(f: =>Unit): Long = {
    var i = 0
    val t1 = System.nanoTime
    while (i < nRounds) {
      f
      i += 1
    }
    System.nanoTime - t1
  }

  def objRef (o: AnyRef): String = Integer.toHexString(System.identityHashCode(o))

  /**
    * a interface for something that can perform queries over iterables
    *
    * normally, implementations involve a query parser and a function that applies
    * the compiled query to the provided items
    *
    * note this interface does not expose if the last parsed query is cached for
    * efficiency reasons
    */
  trait Query[T] {
    def error (msg: String): Unit // error report function
    def getMatchingItems(query: String, items: Iterable[T]): Iterable[T]
  }

  /**
    * a Seq that is associated with a non-element value
    */
  trait AssocSeq [+T,U] extends Seq[T] {
    def assoc: U
  }

  /**
    * something that can parse byte array data
    */
  trait Parser {
    def parse (bs: Array[Byte], off: Int, length: Int): Option[Any]
    def parse (bs: ByteSlice): Option[Any] = parse(bs.data, bs.off, bs.len)
  }
}