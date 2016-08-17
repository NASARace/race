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

package gov.nasa.race.common

/**
  * a partial function that catches all exceptions during evaluation of a PartialFunction, including
  * the ones that happen during unapply (i.e. the matching part)
  */
class CatchAllPartialFunction[-A, +B](pf: PartialFunction[A, B], exceptionHandler: Throwable=>Unit) extends PartialFunction[A, B] {
  private[this] var cached = false
  private[this] var argCache: A = _
  private[this] var validCache = false
  private[this] var resCache: B = _

  private[this] def genericEquality(a0: A, a1: A) = a0 match {
    case _: Unit | _: Boolean | _: Byte | _: Short | _: Char |
         _: Int | _: Long | _: Float | _: Double => a1 == a0
    case _ => a0.asInstanceOf[AnyRef] eq a1.asInstanceOf[AnyRef]
  }

  def isDefinedAt (a: A) = {
    if (!cached || !genericEquality(argCache, a)) {
      cached = true
      argCache = a
      try {
        validCache = pf.isDefinedAt(a)
        if (validCache) resCache = pf(a)
      }
      catch {
        case e: Throwable =>
          exceptionHandler(e)
          validCache = false
      }
    }
    validCache
  }

  def apply (a: A) = {
    def throwIAE = throw new IllegalArgumentException(s"partial function not defined at: $a")

    if (!cached || !genericEquality(argCache, a)) {
      if (!isDefinedAt(a)) throwIAE
    }
    else if (!validCache) throwIAE
    resCache
  }
}
