/*
 * Copyright (c) 2021, United States Government, as represented by the
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
  * Scala wrapper for JEP414 SIMD incubator api
  *
  * NOTE - as of JDK 17.0.1 (11/21) commented out since it creates a JDK 16 dependency without performance gains
  *
  * SIMD results for simple computations are actually slower than explicit element ops, and only approach break even
  * for large numbers of executions. In addition, the jdk.incubator.vector api also still crashes on certain vector sizes
  * We might resurrect this once the API is stable and offers performance improvement
  */

import jdk.incubator.vector._
import java.lang.{Double => JDouble}

sealed abstract class Vec[T, JT, V <: Vector[JT]] {
  protected val species: VectorSpecies[JT]

  protected def mask (offset: Int): VectorMask[JT]
  protected def vecFromArray (a: Array[T], offset: Int): V
  protected def vecFromArray (a: Array[T], offset: Int, mask: VectorMask[JT]): V
  protected def vecIntoArray (v: V, a: Array[T], offset: Int): Unit
  protected def vecIntoArray (v: V, a: Array[T], offset: Int, mask: VectorMask[JT]): Unit

  //--- the public api (more to follow - array arg groups are separated for input/output

  def compute (a: Array[T], b: Array[T]) (c: Array[T]) (f: (V,V)=>V): Unit = {
    var i = 0
    val n = species.length

    //--- full vectors
    while (i < a.length) {
      val va = vecFromArray(a,i)
      val vb = vecFromArray(b,i)
      val vc = f(va,vb)
      vecIntoArray(vc,c,i)
      i += n
    }

    //--- partial vector
    if (i > a.length) {
      i -= n
      val m = mask(i)
      val va = vecFromArray(a,i, m)
      val vb = vecFromArray(b,i, m)
      val vc = f(va,vb)
      vecIntoArray(vc,c,i, m)
    }
  }
}

object DoubleVec {
  implicit class RichDoubleVector (val v: DoubleVector) extends AnyVal {
    def + (a: DoubleVector): DoubleVector = v.add(a)
    def - (a: DoubleVector): DoubleVector = v.sub(a)
    def * (a: DoubleVector): DoubleVector = v.mul(a)
    def / (a: DoubleVector): DoubleVector = v.div(a)

    def +: (a: DoubleVector)(implicit m: VectorMask[JDouble]): DoubleVector = v.add(a,m)
    def -: (a: DoubleVector)(implicit m: VectorMask[JDouble]): DoubleVector = v.sub(a,m)
    def *: (a: DoubleVector)(implicit m: VectorMask[JDouble]): DoubleVector = v.mul(a,m)
    def /: (a: DoubleVector)(implicit m: VectorMask[JDouble]): DoubleVector = v.div(a,m)
  }
}

class DoubleVec extends Vec[Double, java.lang.Double, DoubleVector] {
  protected val species = DoubleVector.SPECIES_PREFERRED

  protected def mask (offset: Int): VectorMask[JDouble] = species.indexInRange(offset,species.length)
  protected def vecFromArray (a: Array[Double], offset: Int): DoubleVector = DoubleVector.fromArray(species, a, offset)
  protected def vecFromArray (a: Array[Double], offset: Int, mask: VectorMask[JDouble]): DoubleVector = DoubleVector.fromArray(species, a, offset, mask)
  protected def vecIntoArray (v: DoubleVector, a: Array[Double], offset: Int): Unit = v.intoArray(a,offset)
  protected def vecIntoArray (v: DoubleVector, a: Array[Double], offset: Int, mask: VectorMask[JDouble]): Unit = v.intoArray(a,offset,mask)
}

