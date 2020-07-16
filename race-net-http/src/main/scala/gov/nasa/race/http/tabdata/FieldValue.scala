/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.http.tabdata

/**
  * root type for field values
  *
  * Note this has to be a universal trait since we have value classes extending it
  */
sealed trait FieldValue extends Any {
  def toLong: Long
  def toDouble: Double
  def toJson: String
}

case class LongValue (value: Long) extends AnyVal with FieldValue {
  def toLong: Long = value
  def toDouble: Double = value.toDouble
  def toJson = value.toString
}

case class DoubleValue (value: Double) extends AnyVal with FieldValue {
  def toLong: Long = Math.round(value)
  def toDouble: Double = value
  def toJson = value.toString
}
