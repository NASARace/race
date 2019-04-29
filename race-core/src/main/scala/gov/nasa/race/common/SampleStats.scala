/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import scala.annotation.tailrec

/**
  * base trait for sample statistics
  *
  * note this interface does not include methods to manipulate/add samples
  */
trait SampleStats[T] {
  def numberOfSamples: Int
  def mean: T
  def min: T
  def max: T
  def variance: Double
  def sigma: Double = Math.sqrt(variance)
  //.. and maybe more to follow
}

/**
  * a trait for basic online (incremental) sample statistics calculation
  *
  * note - while we could use scala.math.Numeric as a parameterized type constraint this
  * would (a) loose efficiency and (b) would not work for uom AnyVals which do not support
  * automatic conversion to primitives and a full range of arithmetic ops (what would multiplication
  * mean for Date?). This means the conversion to Double should happen in a SampleStats implementor
  * that just delegates to OnlineSampleStats
  */
class OnlineSampleStats extends SampleStats[Double] with XmlSource {
  var numberOfSamples: Int = 0
  var min: Double = Double.PositiveInfinity
  var max: Double = Double.NegativeInfinity
  var mean: Double = 0

  protected var isMax = false
  protected var isMin = false

  protected var s: Double = 0

  // Welford's algorithm
  def addSample (x: Double): Unit = {
    val m = mean
    val k = numberOfSamples + 1

    val mNext = m + (x - m)/k
    s += (x - m) * (x - mNext)
    mean = mNext
    numberOfSamples = k

    isMin = (x < min)
    if (isMin) min = x

    isMax = (x > max)
    if (isMax) max = x
  }

  @inline def += (x: Double): Unit = addSample(x)

  @inline def isMinimum: Boolean = isMin
  @inline def isMaximum: Boolean = isMax

  @inline def variance: Double = s / (numberOfSamples-1)

  def toXML = <samples>
    <count>{numberOfSamples}</count>
    <min>{min}</min>
    <max>{max}</max>
    <mean>{mean}</mean>
    <variance>{variance}</variance>
  </samples>
}

object BucketCounter {
  def singleBucketCounter = new BucketCounter(Double.MinValue,Double.MaxValue,1)
}

/**
  * a simple counter for samples.
  *
  * While this could be abstracted over the numeric type by means of an implicit Ordering[T]
  * parameter, this is of little value given that the default ctor computes the bucket size
  * which hence needs to be a Double. We don't store anything of the value type
  *
  * Note that instances of BucketCounter are not thread safe
  */
class BucketCounter (val low: Double, val high: Double, val nBuckets: Int, val useOutliers: Boolean = false)
                                                          extends OnlineSampleStats with Cloneable {
  var lower: Int = 0     // low outliers
  var higher: Int = 0    // high outliers
  val bucketSize = (high - low) / nBuckets
  private var buckets = new Array[Int](nBuckets)

  @inline def bucketIndex (v: Double): Int = ((v - low) / bucketSize).toInt

  override def addSample (v: Double): Unit = {
    if (v < low) {
      lower += 1
      if (useOutliers) super.addSample(v)
    } else if (v >= high) {
      higher += 1
      if (useOutliers) super.addSample(v)
    } else {
      buckets(bucketIndex(v)) += 1
      super.addSample(v)
    }
  }

  def processBuckets (f: (Int,Int)=>Unit): Unit = {
    // avoid iterator
    @tailrec @inline def _proc (i: Int): Unit = {
      if (i<nBuckets) {
        f(i,buckets(i))
        _proc(i+1)
      }
    }
    _proc(0)
  }

  def showBuckets: String = {
    val sb = new StringBuffer
    sb.append(s"$lower |")
    for (i <- 0 until nBuckets){
      val bl = low + (i*bucketSize)
      sb.append(s"$bl: ${buckets(i)} |")
    }
    sb.append(s" $higher")
    sb.toString
  }

  override def clone: BucketCounter = {
    val bc = super.clone.asInstanceOf[BucketCounter]
    bc.buckets = buckets.clone
    bc
  }
}
