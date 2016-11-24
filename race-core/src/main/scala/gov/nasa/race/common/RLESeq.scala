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

import java.util.NoSuchElementException

import gov.nasa.race.util.MixedNumberTokenIterator

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * generic iterator for run length encoded sequences of numeric values
 */
object RLESeq {

  // we do this in two stages so that we can avoid retrieving the next value
  // by means of the runtime reflection based it.getNextNumber[T]
  private def parseVarPairs[T <:AnyVal :ClassTag](s: String, f: MixedNumberTokenIterator=>T) = {
    val values = ArrayBuffer[T]()
    val reps = ArrayBuffer[Int]()
    val it = new MixedNumberTokenIterator(s)
    var len = 0

    while (it.hasNext) {
      val v: T = f(it)
      if (it.hasNext){
        val r = it.nextInt()
        assert (r>0)
        values += v
        reps += r
        len += r
      }
    }
    new RLESeq[T](values.toArray[T],reps.toArray[Int], len)
  }

  def parseBytes(s: String) = parseVarPairs[Byte](s, (i)=>i.nextByte())
  def parseShorts(s: String) = parseVarPairs[Short](s, (i)=>i.nextShort())
  def parseInts(s: String) = parseVarPairs[Int](s, (i)=>i.nextInt())
  def parseLongs(s: String) = parseVarPairs[Long](s, (i)=>i.nextLong())
  def parseDoubles(s: String) = parseVarPairs[Double](s, (i)=>i.nextDouble())
  def parseFloats(s: String) = parseVarPairs[Float](s, (i)=>i.nextFloat())

  // the version for explicitly known numbers of pairs
  private def parseFixedPairs[T <:AnyVal :ClassTag](s: String, nPairs: Int, f: MixedNumberTokenIterator=>T) = {
    val values = new Array[T](nPairs)
    val reps = new Array[Int](nPairs)
    val it = new MixedNumberTokenIterator(s)
    var i = 0
    var len = 0

    while (it.hasNext && i < nPairs) {
      val v: T = f(it)
      if (it.hasNext){
        val r = it.nextInt()
        assert (r>0)
        values(i) = v
        reps(i) = r
        len += r
        i += 1
      }
    }
    assert (nPairs == i)

    new RLESeq[T](values,reps, len)
  }

  def parseBytes(s: String, len: Int) = parseFixedPairs[Byte](s, len, (i)=>i.nextByte())
  def parseShorts(s: String, len: Int) = parseFixedPairs[Short](s, len, (i)=>i.nextShort())
  def parseInts(s: String, len: Int) = parseFixedPairs[Int](s, len, (i)=>i.nextInt())
  def parseLongs(s: String, len: Int) = parseFixedPairs[Long](s, len, (i)=>i.nextLong())
  def parseDoubles(s: String, len: Int) = parseFixedPairs[Double](s, len, (i)=>i.nextDouble())
  def parseFloats(s: String, len: Int) = parseFixedPairs[Float](s, len, (i)=>i.nextFloat())
}

/**
 * this is mostly a storage format, to efficiently create iterators.
 * Focus is on saving memory, not providing efficient accessors (other than
 * the Iterator)
 *
 * <2do> nonetheless, extend this to a full Seq
 */
class RLESeq[T <: AnyVal](val data: Array[T], val repetitions: Array[Int], val length: Int) extends Iterable[T] {
  def this(data: Array[T], repetitions: Array[Int]) = this(data,repetitions, repetitions.sum)

  override def iterator: Iterator[T] = new RLEIterator(data,repetitions)
}

class RLEIterator[T <: AnyVal](data: Array[T], repetitions: Array[Int]) extends Iterator[T] {
  require(data.length > 0 && data.length == repetitions.length)
  final val maxPairIndex = data.length-1

  protected[this] var pairIndex = 0
  protected[this] var value: T = data(0)
  protected[this] var remainingCount = repetitions(0)

  override def hasNext: Boolean = remainingCount > 0 || pairIndex < maxPairIndex

  override def next(): T = {
    if (remainingCount == 0) {
      if (pairIndex < maxPairIndex){
        pairIndex += 1
        value = data(pairIndex)
        remainingCount = repetitions(pairIndex)-1
        assert (remainingCount >= 0)  // we don't support 0 rep counts
      } else throw new NoSuchElementException
    } else {
      remainingCount -= 1
    }
    value
  }
}
