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

/**
  * functions to manage bit fields
  */
object BitUtils {

  @inline def setBits(data: Int, flagMask: Int, cond: Boolean): Int = {
    if (cond) data | flagMask else data & ~flagMask
  }
  @inline def setBits(data: Int, flagMask: Int): Int = data | flagMask
  @inline def clearBits(data: Int, flagMask: Int): Int = data & ~flagMask

  @inline def clearAllBits(data: Int): Int = 0
  @inline def setAllBits(data: Int): Int = 0xffffffff

  @inline def allBitsSet(data: Int, mask: Int): Boolean = (data & mask) == mask
  @inline def anyBitsSet(data: Int, mask: Int): Boolean = (data & mask) != 0

  //... and more to follow
}
