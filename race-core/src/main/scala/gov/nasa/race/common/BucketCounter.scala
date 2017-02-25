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

/**
  * a trait for basic on-the-fly sample statistics calculation
  */
trait SampleStats {
  var nSamples: Int = 0
  var mean: Double = 0.0
  var variance: Double = 0.0    // incrementally computed

  private var sum: Double = 0.0  // more efficient than incremental mean calculation

  def add (v: Double): Unit = {
    sum += v
    nSamples += 1

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
class BucketCounter (val low: Double, val high: Double, val nBuckets: Int) extends SampleStats {
  var lower: Int = 0     // low outliers
  var higher: Int = 0    // high outliers
  val buckets = new Array[Int](nBuckets)
  val bucketSize = (high - low) / nBuckets

  @inline def bucketIndex (v: Double): Int = ((v - low) / bucketSize).toInt

  override def add (v: Double): Unit = {
    if (v < low) lower += 1
    else if (v > high) higher += 1
    else buckets(bucketIndex(v)) += 1

    super.add(v)
  }

  def showBuckets: String = {
    val sb = new StringBuffer
    sb.append(s"[<$low]:$lower,")
    for (i <- 0 until nBuckets){
      val bl = low + (i*bucketSize)
      val bh = bl + bucketSize
      sb.append(s"[$bl..$bh[:${buckets(i)},")
    }
    sb.append(s"[>$high]:$higher")
    sb.toString
  }
}
