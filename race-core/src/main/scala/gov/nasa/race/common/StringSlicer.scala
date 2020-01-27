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
  * utility class to convert String objects into Slices
  * note that it is up to the client if the StringDataBuffer is re-used between different StringSlicers
  */
class StringSlicer (createBuffer: =>StringDataBuffer) {

  lazy private val _bb: StringDataBuffer = createBuffer
  private val slice = MutUtf8Slice.empty

  def slice(s: String): Utf8Slice = {
    _bb.encode(s)
    slice.set(_bb.data,0,_bb.len)
    slice
  }
}
