/*
 * Copyright (c) 2018, United States Government, as represented by the
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
  * a modifiable 2-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
class T2 [A,B] (var _1: A, var _2: B) extends Product2[A,B] {
  override def canEqual (other: Any): Boolean = other.isInstanceOf[Product2[A,B]]

  def toTuple: Tuple2[A,B] = (_1, _2)
  def ?= (other: Product2[A,B]): Boolean = (other != null) && (other._1 == _1) && (other._2 == _2)
  def set (a: A, b: B): Unit = { _1 = a; _2 = b }
}


/**
  * a modifiable 3-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
class T3 [A,B,C] (var _1: A, var _2: B, var _3: C) extends Product3[A,B,C] {
  override def canEqual (other: Any): Boolean = other.isInstanceOf[Product3[A,B,C]]

  def toTuple: Tuple3[A,B,C] = (_1, _2, _3)
  def ?= (other: Product3[A,B,C]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3)
  }
  def set (a: A, b: B, c: C): Unit = { _1 = a; _2 = b; _3 = c }
}

/**
  * a modifiable 4-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
class T4 [A,B,C,D] (var _1: A, var _2: B, var _3: C, var _4: D) extends Product4[A,B,C,D] {
  override def canEqual (other: Any): Boolean = other.isInstanceOf[Product4[A,B,C,D]]

  def toTuple: Tuple4[A,B,C,D] = (_1, _2, _3, _4)
  def ?= (other: Product4[A,B,C,D]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3) && (other._4 == _4)
  }
  def set (a: A, b: B, c: C, d: D): Unit = { _1 = a; _2 = b; _3 = c; _4 = d }
}


//--- specializations for timed doubles

class LD (l: Long, d1: Double) extends T2[Long,Double](l,d1) {
  def this() = this(0, 0.0)
  def this (t: (Long,Double)) = this(t._1, t._2)
}

class LDD (l: Long, d1: Double, d2: Double) extends T3[Long,Double,Double](l,d1,d2) {
  def this() = this(0, 0.0, 0.0)
  def this (t: (Long,Double,Double)) = this(t._1, t._2, t._3)
}

class LDDD (l: Long, d1: Double, d2: Double, d3: Double) extends T4[Long,Double,Double,Double](l,d1,d2,d3) {
  def this() = this(0, 0.0, 0.0, 0.0)
  def this (t: (Long,Double,Double,Double)) = this(t._1, t._2, t._3, t._4)
}
