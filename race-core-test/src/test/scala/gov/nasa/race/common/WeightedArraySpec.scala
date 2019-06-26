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

import java.util

import gov.nasa.race._
import gov.nasa.race.test.RaceSpec
import org.scalacheck.Gen
import org.scalacheck.Gen._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

/**
  * unit test for gov.nasa.race.common.WeightedArray
  */
object WeightedArraySpec {
  class WeightedStringEntry (var obj: String, var weight: Double) extends WeightedArray.Entry[String]

  class WeightedStringArray (max: Int) extends WeightedArray[String,WeightedStringEntry] {
    val array = new Array[WeightedStringEntry](max)
    override def createEntry(s: String, w: Double) = new WeightedStringEntry(s,w)

    def dump = {
      if (_size > 0) {
        var i = 0
        foreach { e =>
          println(f"   [$i]: ${e.weight}%6.2f = '${e.obj}'")
          i += 1
        }
      } else {
        println("    empty")
      }
    }

    def checkOrder: Boolean = {
      var lastW = Double.MinValue
      foreach { e =>
        if (e.weight < lastW) return false
        lastW = e.weight
      }
      true
    }
  }

  def checkExpected (wsa: WeightedStringArray, expected: Array[(Double,String)]) = {
    assert (wsa.size == expected.length)
    for (i <- 1 until wsa.size) {
      val e = wsa(i)
      val ex = expected(i)
      assert (e.weight == ex._1 && e.obj == ex._2 )
    }
    println("expected values check out.")
  }

  def addAll (wsa: WeightedStringArray, es:(String,Double)*) = {
    es.foreach( e => wsa.tryAdd(e._1, e._2))
  }
}


/**
  * unit test for WeightedArray
  */
class WeightedArraySpec extends AnyFlatSpec with RaceSpec {
  import WeightedArraySpec._

  "fixed sequence of operations" should "reproduce known list" in {
    println("--------------- basic fixed sequence test")
    val wa = new WeightedStringArray(4)
    var idx = wa.tryAdd("42", 42.0)
    idx = wa.tryAdd("100", 100.0)
    idx = wa.tryAdd("4", 4.0)
    idx = wa.tryAdd("42", 42.42) // should only replace prev entry
    idx = wa.tryAdd("84", 84.0) // now we are full
    idx = wa.tryAdd("120", 120.0) // should be ignored
    idx = wa.tryAdd("77", 77.0) // should bump 100 off the list
    idx = wa.tryAdd("80", 80.0) // should replace 84 (last item)
    idx = wa.tryAdd("80", 80.42) // should update last entry 80
    idx = wa.tryAdd("4", 4.42) // should update first entry
    idx = wa.tryAdd("77", 35.0) // should change position of 70 from idx 2 to 1
    println(s"$wa")
    checkExpected(wa, Array((4.42,"4"),(35.0,"77"),(42.42,"42"),(80.42,"80")))
  }

  //--- random sequences

  val wGen: Seq[Gen[Double]] = {
    var list = ArrayBuffer(choose(0.0, 10.0))
    repeat(10) { list += choose(0.0, 10.0) }
    list
  }
  val weightSequences = sequence[Array[Double],Double](wGen)

  "random sequence of operations" should "maintain order and bounds" in {
    println("--------------- random sequence test")

    forAll(weightSequences) { weights =>
      val wa = new WeightedStringArray(4)
      for (w <- weights) wa.tryAdd( f"$w%.1f", w)

      assert(wa.size > 0 && wa.size <= 4)
      print(s"$wa ")
      assert(wa.checkOrder)
      println(" Ok.")
    }
  }

  //---- perturbations
  def updateWeights (wa: WeightedStringArray, parray: Array[Double]) = {
    var i = -1
    wa.updateWeights( e => {
      i += 1
      e.weight + parray(i)
    })
  }

  //--- random re-order
  val perturbations: Gen[Array[Double]] = for {
    p0 <- choose(-5.0,5.0)
    p1 <- choose(-5.0,5.0)
    p2 <- choose(-5.0,5.0)
    p3 <- choose(-5.0,5.0)
  } yield Array(p0,p1,p2,p3)

  "random weight updates" should "maintain order and size" in {
    println("--------------- random weight update test")

    forAll(perturbations) { parray =>
      val wa = new WeightedStringArray(4)
      for (w <- Array(0.7,1.5,3.3,7.0)) wa.tryAdd(f"$w%.2f", w)

      updateWeights(wa,parray)
      print(s"$wa")
      assert(wa.checkOrder)
      println(" Ok.")
    }
  }

  "fixed weight update" should "maintain order" in {
    println("--------------- single weight update test")
    val wa = new WeightedStringArray(4)
    wa.tryAdd("0.7", 0.7)
    wa.tryAdd("1.5", 1.5)
    wa.tryAdd("3.3", 3.3)
    wa.tryAdd("7.0", 7.0)

    val parray = Array(1.0, -1.0, -0.3, 2.0)

    updateWeights(wa,parray)
    print(s"$wa")
    assert(wa.checkOrder)
    println(" Ok.")
  }

  "filter function" should "remove known items" in {
    println("--------------- entry filter test")
    val wa = new WeightedStringArray(4)
    wa.tryAdd("1.0", 1.0)
    wa.tryAdd("1.5", 1.5)
    wa.tryAdd("3.3", 3.3)
    wa.tryAdd("7.0", 7.0)

    assert(wa.removeEvery(e=>e.obj.startsWith("1.")))
    print(s"$wa")
    assert(wa.size == 2)
    assert(wa.checkOrder)
    println(" Ok.")
  }

  "weight update" should "swap positions upwards" in {
    println("--------------- move element up")
    val wa = new WeightedStringArray(4)
    wa.tryAdd("AAL477", 4296)
    wa.tryAdd("CPZ5966", 18805)
    wa.tryAdd("AAL2352", 19027)
    wa.tryAdd("N210MT", 26762)

    // swap 1/2
    println(s"before: $wa")
    println(" + (19947,CPZ5966) should swap 1/2")
    wa.tryAdd("CPZ5966", 19947)
    println(s"after:  $wa")
    checkExpected(wa, Array((4296.0,"AAL477"),(19027.0,"AAL2352"),(19947.0,"CPZ5966"),(26762.0,"N210MT")))

    // swap 2/3
    println("+ (27000,CPZ5966) should swap 2/3")
    wa.tryAdd("CPZ5966", 27000.0)
    println(s"after:  $wa")
    checkExpected(wa, Array((4296.0,"AAL477"),(19027.0,"AAL2352"),(26762.0,"N210MT"),(27000.0,"CPZ5966")))

    // keep 3
    println("+ (28888.8,CPZ5966) no swap, last position")
    wa.tryAdd("CPZ5966", 28888.8)
    println(s"after:  $wa")
    checkExpected(wa, Array((4296.0,"AAL477"),(19027.0,"AAL2352"),(26762.0,"N210MT"),(28888.8,"CPZ5966")))
  }

  "weight update" should "swap position downwards" in {
    println("--------------- move element down")
    val wa = new WeightedStringArray(4)
    addAll(wa, ("SKW5989",28206),("VIR41",32776),("ASA393",33512),("UAL1687",33538))
    println(s"before: $wa + (33492,UAL1687)")
    wa.tryAdd("UAL1687",33492)
    println(s"after:  $wa")
    assert(wa.checkOrder)
    println("order Ok.")
  }
}
