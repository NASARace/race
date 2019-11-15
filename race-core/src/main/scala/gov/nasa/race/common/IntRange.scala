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

object IntRange {
  def apply (off: Int, len: Int) = new IntRange((((len & 0x00000000ffffffffL) << 32) + off))
}

/**
  * value class that encapsulates a offset/length range
  *
  * note there is a scala.collection.immutable.Range we have to disambiguate from
  */
class IntRange(protected val r: Long) extends AnyVal {
  def offset: Int = (r & 0xffffffff).toInt
  def length: Int = ((r >> 32) & 0xffffffff).toInt

  override def toString: String = s"IntRange($offset,$length)"
}
