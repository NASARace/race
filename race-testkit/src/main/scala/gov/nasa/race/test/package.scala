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

package gov.nasa.race

/**
  * package `gov.nasa.race.test` contains types and utilities that are used to create RACE specific tests
  */
package object test {

  def elapsedMillis(n: Int)(f: => Any): Long = {
    val t1 = System.currentTimeMillis
    var i = 0
    while (i < n) {
      f
      i += 1
    }
    val t2 = System.currentTimeMillis
    t2 - t1
  }
}
