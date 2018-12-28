/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.track

import gov.nasa.race.IdentifiableObject
import gov.nasa.race.util.StringUtils

import scala.reflect.{ClassTag, classTag}

object TrackedObject {

  //--- track status flags
  // note the lower 2 bytes are reserved for general flags defined here,
  // channel specific flags should use the upper two

  final val TrackNoStatus: Int  = 0x00
  final val NewFlag: Int        = 0x01
  final val ChangedFlag: Int    = 0x02
  final val DroppedFlag: Int    = 0x04
  final val CompletedFlag: Int  = 0x08
  final val FrozenFlag: Int     = 0x10

  def tempCS (flightId: String) = "?" + flightId
  def isTempCS (cs: String) = cs.charAt(0) == '?'

  // c/s changes only happen rarely, and if they do we want to preserve the changed value
  // for all downstream actors so we don't use a fixed field for it
  case class ChangedCS(oldCS: String)

  case class TrackProblem(fpos: TrackedObject, lastFpos: TrackedObject, problem: String)
}

/**
  * this is the abstract type used for sea, land, air and space objects we can track
  *
  * in addition to the super traits, TrackedObjects provide an API to attach arbitrary
  * non-type-constraint data to its instances. This is a generic extension mechanism for
  * cases where occasionally associated data (a) should not be type restricted and (b)
  * are set too rarely to waste storage on all TrackObject instances
  */
trait TrackedObject extends IdentifiableObject with TrackPoint with MovingObject with TrackMessage {
  import TrackedObject._

  // generic mechanism to dynamically attach per-event data to track objects
  var amendments = List.empty[Any]

  def status: Int // flag field, can be channel specific (hence no enum)

  def isNew = (status & NewFlag) != 0
  def isChanged = (status & ChangedFlag) != 0
  def isDropped = (status & DroppedFlag) != 0
  def isCompleted = (status & CompletedFlag) != 0
  def isDroppedOrCompleted = (status & (DroppedFlag|CompletedFlag)) != 0
  def isFrozen = (status & FrozenFlag) != 0

  def amend (a: Any): TrackedObject = { amendments = a +: amendments; this }
  def amendAll (as: Any*) = { as.foreach(amend); this }
  def getAmendment (f: (Any)=> Boolean): Option[Any] = amendments.find(f)
  def getFirstAmendmentOfType[T: ClassTag]: Option[T] = {
    val tgtCls = classTag[T].runtimeClass
    amendments.find( a=> tgtCls.isAssignableFrom(a.getClass)).map( _.asInstanceOf[T])
  }

  def toShortString = {
    val d = date
    val hh = d.getHourOfDay
    val mm = d.getMinuteOfHour
    val ss = d.getSecondOfMinute
    val id = StringUtils.capLength(cs)(8)
    f"$id%-7s $hh%02d:$mm%02d:$ss%02d ${position.altitude.toFeet.toInt}%6dft ${heading.toNormalizedDegrees.toInt}%3d° ${speed.toKnots.toInt}%4dkn"
  }

  def hasTempCS = isTempCS(cs)
  def tempCS = if (hasTempCS) cs else TrackedObject.tempCS(id)
  def getOldCS: Option[String] = amendments.find(_.isInstanceOf[ChangedCS]).map(_.asInstanceOf[ChangedCS].oldCS)

}

trait TrackedObjectEnumerator {
  def numberOfTrackedObjects: Int
  def foreachTrackedObject (f: TrackedObject=>Unit)
}