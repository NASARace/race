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

package gov.nasa.race.util

import java.io._
import java.util.NoSuchElementException

import scala.reflect.runtime.universe._

/**
 * an iterator over a StreamTokenizer that supports heterogenous number types
 */
class MixedNumberTokenIterator (r: Reader) extends StreamTokenizer(r) with StreamTokenizerExtras with Iterator[AnyVal]{
  def this (is: InputStream) = this(new InputStreamReader(is))
  def this (s: String) = this(new StringReader(s))

  parseNumbers()  // note - this allows decimals in integral specs, which causes silent truncation
  eolIsSignificant(false)
  setCategory(whitespaceChars, ' ', ',', '\t')

  protected[this] var exception: Option[Throwable] = None
  protected[this] var nextElem: Option[Double] = advance

  def lastException = exception

  override def hasNext: Boolean = nextElem.isDefined

  //--- these are the type specific accessors to be used by clients
  //    (note - we should do value range checks here)

  def nextByte()= next().toByte
  def nextShort() = next().toShort
  def nextInt() = next().toInt
  def nextLong() = next().toLong
  def nextFloat() = next().toFloat
  def nextDouble() = next()

  // note - this isn't very efficient so use the explicit versions outside of generic contexts
  def nextNumber[T <:AnyVal :TypeTag](): T = {
    val t = typeOf[T]
    if (t <:< typeOf[Byte]) nextByte().asInstanceOf[T]
    else if (t <:< typeOf[Short]) nextShort().asInstanceOf[T]
    else if (t <:< typeOf[Int]) nextInt().asInstanceOf[T]
    else if (t <:< typeOf[Long]) nextLong().asInstanceOf[T]
    else if (t <:< typeOf[Float]) nextFloat().asInstanceOf[T]
    else if (t <:< typeOf[Double]) nextDouble().asInstanceOf[T]
    else throw new IllegalArgumentException("unknown number type")
  }

  /**
   * this is not supposed to be used directly because it only returns Doubles
   */
  override def next(): Double = {
    if (nextElem.isEmpty) throw new NoSuchElementException else {
      val n = nextElem.get
      nextElem = advance
      n
    }
  }

  def advance: Option[Double] = {
    try {
      nextToken match {
        case StreamTokenizer.TT_NUMBER => Some(nval)
        case StreamTokenizer.TT_WORD => None // we shouldn't get any
        case other => None
      }
    } catch {
      case t: Throwable =>
        exception = Some(t)
        None
    }
  }
}
