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
  * base trait for sample statistics that incrementally keep track of min,max,mean,sigma of a dynamic sample set
  */
trait SampleStats[T <: AnyVal] {
  protected var _n: Int

  def numberOfSamples: Int = _n

  def isEmpty: Boolean = _n == 0
  def nonEmpty: Boolean = _n > 0

  //--- note these all have to be valid sample values
  def min: T
  def max: T
  def mean: T  // this has to be a valid sample value

  //--- computed statistical values (Doubles)
  def mu: Double // the population mean 'μ'
  @inline def μ = mu

  def variance: Double  // the population variance 'σ²'
  @inline def σ2 = variance
  def sampleVariance: Double // estimate of population variance if samples ⊂ population

  def sigma: Double = Math.sqrt(variance)
  def sampleSigma = Math.sqrt(sampleVariance)

  //.. and maybe more to follow

  // we could provide default implementations based on implicits (Ordering, NumericOps)
  // to reduce source redundant implementations but this would be vastly less efficient than
  // directly using primitive types
  def addSample (x: T): Unit
  @inline final def += (x: T): Unit = addSample(x) // just an alias
  def isMinimum (x: T): Boolean
  def isMaximum (x: T): Boolean
}

// note that we can't define mean,min,max in the impl traits since their SampleStats
// interface requires the generic type in both co- and contra-variant positions.
// These methods have to be defined in concrete classes or it will cause name conflicts

trait OnlineSampleStatsImpl {
  protected var _n: Int = 0
  protected var _mean: Double = 0
  protected var s: Double = 0

  @inline def variance: Double = {
    if (_n > 1) s / _n
    else if (_n == 1) 0.0
    else Double.NaN // no sample yet - undefined
  }

  @inline def sampleVariance: Double = {
    if (_n > 1) s / (_n - 1)
    else if (_n == 1) 0.0
    else Double.NaN // no sample yet - undefined
  }

  @inline def mu: Double = _mean
}

/**
  * implementation construct to factor out Double based computation so that
  * we don't have to duplicate all addSample and min/max/mean methods in concrete SampleStats
  * (note that generic T appears in co- and contra-variant positions in the SampleStats interface)
  */
trait OnlineSampleStatsImplD extends OnlineSampleStatsImpl with XmlSource {
  protected var _min: Double = Double.PositiveInfinity
  protected var _max: Double = Double.NegativeInfinity

  protected def addSampleD (x: Double): Unit = {
    _n += 1

    val delta = x - _mean
    _mean += delta / _n
    val deltaʹ = x - _mean
    s += delta * deltaʹ

    if (x < _min) _min = x
    if (x > _max) _max = x
  }

  def toXML: String = {
    s"""    <samples>
      <count>${_n}</count>
      <min>${_min}</min>
      <max>${_max}</max>
      <mean>${_mean}</mean>
      <variance>$variance</variance>
    </samples>"""
  }
}

/**
  * likewise for Long basic type
  */
trait OnlineSampleStatsImplJ extends OnlineSampleStatsImpl with XmlSource {
  protected var _min: Long = Long.MaxValue
  protected var _max: Long = Long.MinValue

  protected def addSampleJ (x: Long): Unit = {
    _n += 1

    val delta = x - _mean
    _mean += delta / _n
    val deltaʹ = x - _mean
    s += delta * deltaʹ

    if (x < _min) _min = x
    if (x > _max) _max = x
  }

  def toXML: String = {
    s"""    <samples>
      <count>${_n}</count>
      <min>${_min}</min>
      <max>${_max}</max>
      <mean>${_mean}</mean>
      <variance>$variance</variance>
    </samples>"""
  }
}

/**
  * a trait for basic online (incremental) sample statistics calculation of Double values
  */
class DoubleStats extends SampleStats[Double] with OnlineSampleStatsImplD {
  @inline override def addSample (v: Double): Unit = addSampleD(v)

  @inline def min: Double = _min
  @inline def max: Double = _max

  @inline def isMinimum (v: Double): Boolean = v <= _min
  @inline def isMaximum (v: Double): Boolean = v >= _max

  @inline def mean: Double = _mean
}


/**
  * a trait for basic online (incremental) sample statistics calculation of Long values
  */
class LongStats extends SampleStats[Long] with OnlineSampleStatsImplJ {
  @inline override def addSample (v: Long): Unit = addSampleJ(v)

  @inline def min: Long = _min
  @inline def max: Long = _max

  @inline def isMinimum (v: Long): Boolean = v <= _min
  @inline def isMaximum (v: Long): Boolean = v >= _max

  @inline def mean: Long = _mean.round
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
                                                          extends DoubleStats with Cloneable {
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
