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

import scala.language.implicitConversions

object Tx {
  implicit def tupleFromT2[A,B] (t: T2[A,B]): (A,B) = (t._1, t._2)
  implicit def tupleFromT3[A,B,C] (t: T3[A,B,C]): (A,B,C) = (t._1, t._2, t._3)
  implicit def tupleFromT4[A,B,C,D] (t: T4[A,B,C,D]): (A,B,C,D) = (t._1, t._2, t._3, t._4)
}

/**
  * a modifiable 2-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
trait T2 [A,B] extends Product2[A,B] {
  var _1: A
  var _2: B

  override def canEqual (other: Any): Boolean = { // we want to be able to compare with normal tuples
    other match {
      case p: Product2[_,_] =>
        _1.getClass == p._1.getClass &&
          _2.getClass == p._2.getClass
      case _ => false
    }
  }

  def toTuple: (A,B) = (_1, _2)
  def ?= (other: Product2[A,B]): Boolean = (other != null) && (other._1 == _1) && (other._2 == _2)
  @inline final def update (a: A, b: B): Unit = { _1 = a; _2 = b }
  @inline final def updated (a: A, b: B): T2[A,B] = { _1 = a; _2 = b; this }
  @inline final def updated (t: Product2[A,B]): T2[A,B] = { _1 = t._1; _2 = t._2; this }
}


/**
  * a modifiable 3-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
trait T3 [A,B,C]  extends Product3[A,B,C] {
  var _1: A
  var _2: B
  var _3: C

  override def canEqual (other: Any): Boolean = {
    other match {
      case p: Product3[_,_,_] =>
        _1.getClass == p._1.getClass &&
          _2.getClass == p._2.getClass &&
          _3.getClass == p._3.getClass
      case _ => false
    }
  }

  def toTuple: (A,B,C) = (_1, _2, _3)
  def ?= (other: Product3[A,B,C]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3)
  }
  @inline final def update (a: A, b: B, c: C): Unit = { _1 = a; _2 = b; _3 = c }
  @inline final def updated (a: A, b: B, c: C): T3[A,B,C] = { _1 = a; _2 = b; _3 = c; this }
  @inline final def updated (t: Product3[A,B,C]): T3[A,B,C] = { _1 = t._1; _2 = t._2; _3 = t._3; this }
}


/**
  * a modifiable 4-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
trait T4 [A,B,C,D] extends Product4[A,B,C,D] {
  var _1: A
  var _2: B
  var _3: C
  var _4: D

  override def canEqual (other: Any): Boolean = {
    other match {
      case p: Product4[_,_,_,_] =>
        _1.getClass == p._1.getClass &&
          _2.getClass == p._2.getClass &&
          _3.getClass == p._3.getClass &&
          _4.getClass == p._4.getClass
      case _ => false
    }
  }

  def toTuple: (A,B,C,D) = (_1, _2, _3, _4)
  def ?= (other: Product4[A,B,C,D]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3) && (other._4 == _4)
  }
  @inline final def update (a: A, b: B, c: C, d: D): Unit = { _1 = a; _2 = b; _3 = c; _4 = d }
  @inline final def updated (a: A, b: B, c: C, d: D): T4[A,B,C,D] = { _1 = a; _2 = b; _3 = c; _4 = d; this }
  @inline final def updated (t: Product4[A,B,C,D]): T4[A,B,C,D] = { _1 = t._1; _2 = t._2; _3 = t._3; _4 = t._4; this }
}

/**
  * a modifiable 5-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
trait T5 [A,B,C,D,E]  extends Product5[A,B,C,D,E] {
  var _1: A
  var _2: B
  var _3: C
  var _4: D
  var _5: E

  override def canEqual (other: Any): Boolean = {
    other match {
      case p: Product5[_,_,_,_,_] =>
        _1.getClass == p._1.getClass &&
          _2.getClass == p._2.getClass &&
          _3.getClass == p._3.getClass &&
          _4.getClass == p._4.getClass &&
          _5.getClass == p._5.getClass
      case _ => false
    }
  }

  def toTuple: (A,B,C,D,E) = (_1, _2, _3, _4, _5)
  def ?= (other: Product5[A,B,C,D,E]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3) &&
      (other._4 == _4) && (other._5 == _5)
  }
  @inline final def update (a: A, b: B, c: C, d: D, e: E): Unit = {
    _1 = a; _2 = b; _3 = c; _4 = d; _5 = e
  }
  @inline final def updated (a: A, b: B, c: C, d: D, e: E): T5[A,B,C,D,E] = {
    _1 = a; _2 = b; _3 = c; _4 = d; _5 = e
    this
  }
  @inline final def updated (t: Product5[A,B,C,D,E]): T5[A,B,C,D,E] = {
    _1 = t._1; _2 = t._2; _3 = t._3; _4 = t._4; _5 = t._5
    this
  }
}

/**
  * a modifiable 6-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
trait T6 [A,B,C,D,E,F] extends Product6[A,B,C,D,E,F] {
  var _1: A
  var _2: B
  var _3: C
  var _4: D
  var _5: E
  var _6: F

  override def canEqual (other: Any): Boolean = {
    other match {
      case p: Product6[_,_,_,_,_,_] =>
        _1.getClass == p._1.getClass &&
          _2.getClass == p._2.getClass &&
          _3.getClass == p._3.getClass &&
          _4.getClass == p._4.getClass &&
          _5.getClass == p._5.getClass &&
          _6.getClass == p._6.getClass
      case _ => false
    }
  }

  def toTuple: (A,B,C,D,E,F) = (_1, _2, _3, _4, _5, _6)
  def ?= (other: Product6[A,B,C,D,E,F]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3) &&
      (other._4 == _4) && (other._5 == _5) && (other._6 == _6)
  }
  @inline final def update (a: A, b: B, c: C, d: D, e: E, f: F): Unit = {
    _1 = a; _2 = b; _3 = c; _4 = d; _5 = e; _6 = f
  }
  @inline final def updated (a: A, b: B, c: C, d: D, e: E, f: F): T6[A,B,C,D,E,F] = {
    _1 = a; _2 = b; _3 = c; _4 = d; _5 = e; _6 = f
    this
  }
  @inline final def updated (t: Product6[A,B,C,D,E,F]): T6[A,B,C,D,E,F] = {
    _1 = t._1; _2 = t._2; _3 = t._3; _4 = t._4; _5 = t._5; _6 = t._6
    this
  }
}

/**
  * a modifiable 7-tuple
  *
  * can be used in cases where we need a single object holding data but want to avoid allocation
  * by means of a cache object
  */
trait T7 [A,B,C,D,E,F,G]  extends Product7[A,B,C,D,E,F,G] {
  var _1: A
  var _2: B
  var _3: C
  var _4: D
  var _5: E
  var _6: F
  var _7: G

  override def canEqual (other: Any): Boolean = {
    other match {
      case p: Product7[_,_,_,_,_,_,_] =>
        _1.getClass == p._1.getClass &&
          _2.getClass == p._2.getClass &&
          _3.getClass == p._3.getClass &&
          _4.getClass == p._4.getClass &&
          _5.getClass == p._5.getClass &&
          _6.getClass == p._6.getClass &&
          _7.getClass == p._7.getClass
      case _ => false
    }
  }
  def toTuple: (A,B,C,D,E,F,G) = (_1, _2, _3, _4, _5, _6, _7)
  def ?= (other: Product7[A,B,C,D,E,F,G]): Boolean = {
    (other != null) && (other._1 == _1) && (other._2 == _2) && (other._3 == _3) &&
      (other._4 == _4) && (other._5 == _5) && (other._6 == _6) && (other._7 == _7)
  }
  @inline final def update (a: A, b: B, c: C, d: D, e: E, f: F, g: G): Unit = {
    _1 = a; _2 = b; _3 = c; _4 = d; _5 = e; _6 = f; _7 = g
  }
  @inline final def updated (a: A, b: B, c: C, d: D, e: E, f: F, g: G): T7[A,B,C,D,E,F,G] = {
    _1 = a; _2 = b; _3 = c; _4 = d; _5 = e; _6 = f; _7 = g
    this
  }
  @inline final def updated (t: Product7[A,B,C,D,E,F,G]): T7[A,B,C,D,E,F,G] = {
    _1 = t._1; _2 = t._2; _3 = t._3; _4 = t._4; _5 = t._5; _6 = t._6; _7 = t._7
    this
  }
}

//... and more TBD