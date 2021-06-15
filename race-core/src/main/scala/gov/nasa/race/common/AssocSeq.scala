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

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer

/**
  * a Seq that is associated with a non-element value
  */
trait AssocSeq [+T,U] extends Seq[T] {
  def assoc: U
}

/**
  * immutable AssocSeq implementation that is based on an array
  */
class AssocSeqImpl [+T,U](val assoc: U, elems: Array[T]) extends AssocSeq[T,U] {
  override def apply(i: Int): T = elems(i)
  override def length: Int = elems.length
  override def iterator: Iterator[T] = elems.iterator
}

class ArraySeqImpl[+T](elems: Array[T]) extends Seq[T] {
  override def apply(i: Int): T = elems(i)
  override def length: Int = elems.length
  override def iterator: Iterator[T] = elems.iterator
}

/**
  * a mutable AssocSeq
  */
class MutAssocSeqImpl [T,U](initSize: Int) extends ArrayBuffer[T](initSize) with AssocSeq[T,U] {
  var assoc: U = _
}