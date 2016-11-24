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

import scala.reflect.runtime.universe._

/**
 * an generic iterator over a StreamTokenizer for homogenous number types
 *
 * Note: this isn't very efficient due to the runtime reflection based doubleToT() conversion
 * <2do> consider using type classes and specialization
 */
class NumberTokenIterator[T <: AnyVal: TypeTag](r: Reader) extends StreamTokenizer(r) with StreamTokenizerExtras with Iterator[T] {

  def this (is: InputStream) = this(new InputStreamReader(is))
  def this (s: String) = this(new StringReader(s))

  parseNumbers()  // note - this allows decimals in integral specs, which causes silent truncation
  eolIsSignificant(false)
  setCategory(whitespaceChars, ' ', ',', '\t')

  val t = typeOf[T]
  val doubleToT: Double=>T =
    if (t <:< typeOf[Double]) {
      (d) => d.asInstanceOf[T]
    } else if (t <:< typeOf[Float]) {
      (d) => if (d <= Float.MaxValue && d >= Float.MinValue) d.toFloat.asInstanceOf[T] else throw new IllegalArgumentException
    } else if (t <:< typeOf[Byte]) {
      (d) => if (d <= Byte.MaxValue && d >= Byte.MinValue) d.toByte.asInstanceOf[T] else throw new IllegalArgumentException
    } else if (t <:< typeOf[Short]) {
      (d) => if (d <= Short.MaxValue && d >= Short.MinValue) d.toShort.asInstanceOf[T] else throw new IllegalArgumentException
    } else if (t <:< typeOf[Int]) {
      (d) => if (d <= Int.MaxValue && d >= Int.MinValue) d.toInt.asInstanceOf[T] else throw new IllegalArgumentException
    } else if (t <:< typeOf[Long]) {
      (d) => if (d <= Long.MaxValue && d >= Long.MinValue) d.toLong.asInstanceOf[T] else throw new IllegalArgumentException
    } else throw new InvalidClassException("element type not a number")

  protected[this] var exception: Option[Throwable] = None
  protected[this] var nextElem: Option[T] = advance

  def lastException = exception

  override def hasNext: Boolean = nextElem.isDefined

  override def next(): T = {
    if (nextElem.isEmpty) throw new NoSuchElementException else {
      val n = nextElem.get
      nextElem = advance
      n
    }
  }

  def advance: Option[T] = {
    try {
      nextToken match {
        case StreamTokenizer.TT_NUMBER => Some(doubleToT(nval))
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
