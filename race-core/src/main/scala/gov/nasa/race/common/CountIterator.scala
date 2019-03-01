/*
 * Copyright (c) 2019, United States Government, as represented by the
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
  * generic iterator that counts up in 1-increments
  */
class CountUpIterator[T](val iStart: Int, val n: Int)(f:(Int)=>T) extends Iterator[T] {
  val limit = iStart + n
  protected var i = iStart

  override def hasNext: Boolean = i < limit

  override def next(): T = {
    if (i >= limit) throw new NoSuchElementException
    val t = f(i)
    i += 1
    t
  }
}

/**
  * generic iterator that counts down in 1-increments
  */
class CountDownIterator[T](val iStart: Int, val n: Int)(f:(Int)=>T) extends Iterator[T] {
  val limit = iStart - n
  protected var i = iStart

  override def hasNext: Boolean = i > limit

  override def next(): T = {
    if (i <= limit) throw new NoSuchElementException
    val t = f(i)
    i -= 1
    t
  }
}