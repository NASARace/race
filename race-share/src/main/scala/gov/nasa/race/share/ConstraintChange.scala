/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonMessageObject, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime


object ConstraintChange {
  val _constraintChange_ = asc("constraintChange")
  val _date_ = asc("date")
  val _reset_ = asc("reset")
  val _violated_ = asc("violated")
  val _resolved_ = asc("resolved")
}
import ConstraintChange._

/**
  * object that is used to publish changes in ConstraintFormula evaluations
  * this is only sent from the USR to connected devices, hence we don't need a parser
  */
case class ConstraintChange (date: DateTime, reset: Boolean, violated: Seq[ConstraintFormula], resolved: Seq[ConstraintFormula]) extends JsonMessageObject {

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeObjectMember(_constraintChange_) { w=>
      w.writeDateTimeMember(_date_, date)
      w.writeBooleanMember(_reset_, reset)
      if (violated.nonEmpty) w.writeObjectMember(_violated_)(w=> violated.foreach( _.serializeAsMemberObjectTo(w)) )
      if (resolved.nonEmpty) w.writeObjectMember(_resolved_)(w=> resolved.foreach( _.serializeAsMemberObjectTo(w)) )
    }
  }
}
