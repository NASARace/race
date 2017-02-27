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
  * a trait for basic on-the-fly sample statistics calculation
  */
trait SampleStats {
  var nSamples: Int = 0
  var min: Double = Double.PositiveInfinity
  var max: Double = Double.NegativeInfinity
  var mean: Double = Double.NaN
  var variance: Double = 0    // incrementally computed

  private var sum: Double = 0.0  // more efficient than incremental mean calculation

  def add (v: Double): Unit = {
    sum += v
    nSamples += 1

    if (v < min) min = v
    if (v > max) max = v

    val n = nSamples
    if (n > 1){
      variance = (n -2)*variance / (n-1) + squared(v - mean)/n
    }
    mean = sum / nSamples
  }


  @inline def sigma: Double = Math.sqrt(variance)
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
                                                          extends SampleStats with Cloneable {
  var lower: Int = 0     // low outliers
  var higher: Int = 0    // high outliers
  val bucketSize = (high - low) / nBuckets
  private var buckets = new Array[Int](nBuckets)

  @inline def bucketIndex (v: Double): Int = (Math.round((v - low) / bucketSize)).toInt

  override def add (v: Double): Unit = {
    if (v < low) {
      lower += 1
      if (useOutliers) super.add(v)
    } else if (v > high) {
      higher += 1
      if (useOutliers) super.add(v)
    } else {
      buckets(bucketIndex(v)) += 1
      super.add(v)
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
