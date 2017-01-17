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

import scala.language.implicitConversions
import scala.math._

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
  }

  final val UndefinedDouble = Double.NaN
  @inline def isDefined (d: Double) = d != Double.NaN

  def Null[T <: AnyRef]: T = null.asInstanceOf[T]


  /** are d1 and d2 on different sides of threshold */
  @inline def crossedThreshold(d1: Double, d2: Double, threshold: Double): Boolean = {
    Math.signum(d1 - threshold) != Math.signum(d2 - threshold)
  }

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

  trait Counter {
    val counterThreshold: Int
    protected var count = 0

    def incCounter: Boolean = {
      count += 1
      if (count >= counterThreshold){
        count = 0
        true
      } else false
    }
  }
}