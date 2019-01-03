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

import java.lang.Math.abs

/**
  * base for T1Interpolant tests
  */
trait T1InterpolantTest {

  val ε: Double
  implicit var checkError = true

  def testPoint (ts: Array[Long], t: Int)
                (f: (Long)=>Double)
                (g: (Array[Long],Array[Double])=>T1Interpolant)
                (implicit checkError: Boolean) = {
    val vs: Array[Double] = ts.map(f)
    val r = g(ts,vs)
    val v = r.eval(t)
    val u = f(t)
    println(s" r($t) = $v ($u -> e=${u - v})")
    if (checkError) assert( abs(u - v) < ε)
  }

  def testRange (ts: Array[Long], tStart: Int, tEnd: Int, dt: Int)
                (f: (Long)=>Double)
                (g: (Array[Long],Array[Double])=>T1Interpolant)
                (implicit checkError: Boolean) = {
    val vs: Array[Double] = ts.map(f)
    val r = g(ts, vs)
    r.evalForward(tStart,tEnd,dt) { (t, v) =>
      val u = f(t)
      println(s"       r($t) = $v ($u -> e=${u - v})")
      if (checkError) assert( abs(u - v) < ε)
    }
  }

  def testReverseRange(ts: Array[Long], tEnd: Int, tStart: Int, dt: Int)
                      (f: (Long)=>Double)
                      (g: (Array[Long],Array[Double])=>T1Interpolant)
                      (implicit checkError: Boolean) = {
    val vs: Array[Double] = ts.map(f)
    val r = g(ts, vs)
    r.evalReverse(tEnd,tStart,dt) { (t, v) =>
      val u = f(t)
      println(s"       r($t) = $v ($u -> e=${u - v})")
      if (checkError) assert( abs(u - v) < ε)
    }
  }

  def testForwardIterator (ts: Array[Long], tStart: Int, tEnd: Int, dt: Int)
                          (f: (Long)=>Double)
                          (g: (Array[Long],Array[Double])=>T1Interpolant)
                          (implicit checkError: Boolean) = {
    val vs: Array[Double] = ts.map(f)
    val r = g(ts, vs)
    val it = r.iterator(tStart,tEnd,dt)
    while (it.hasNext) {
      val T2(t,v) = it.next
      val u = f(t)
      println(s"       r($t) = $v ($u -> e=${u - v})")
      if (checkError) assert( abs(u - v) < ε)
    }
  }

  def testReverseIterator (ts: Array[Long], tEnd: Int, tStart: Int, dt: Int)
                          (f: (Long)=>Double)
                          (g: (Array[Long],Array[Double])=>T1Interpolant)
                          (implicit checkError: Boolean) = {
    val vs: Array[Double] = ts.map(f)
    val r = g(ts, vs)
    val it = r.reverseIterator(tEnd,tStart,dt)
    while (it.hasNext) {
      val T2(t,v) = it.next
      val u = f(t)
      println(s"       r($t) = $v ($u -> e=${u - v})")
      if (checkError) assert( abs(u - v) < ε)
    }
  }

}
