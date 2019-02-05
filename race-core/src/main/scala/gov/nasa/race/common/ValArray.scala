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
  * generic base of Value class array, which can't be an Array[T] since this is a final class
  *
  * T is the underlying predefined type (Int, Long etc.)
  * U is the value class mapping int T (Length, Angle etc.)
  *
  * TODO - check runtime overhead of converter functions (if they cause allocation that's a show stopper)
  */
abstract class ValArray [T<:AnyVal:Manifest, U<:AnyVal] (protected val data: Array[T],
                                                         dataToElement: (T)=>U,
                                                         elementToData: (U)=>T) {

}
