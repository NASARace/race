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

/**
  * this is the abstract type used for sea, land, air and space objects we can track
  *
  * in addition to the super traits, TrackedObjects provide an API to attach arbitrary
  * non-type-constraint data to its instances. This is a generic extension mechanism for
  * cases where occasionally associated data (a) should not be type restricted and (b)
  * are set too rarely to waste storage on all TrackObject instances
  */
trait TrackedObject extends IdentifiableObject with TrackPoint3D with MovingObject {

  // generic mechanism to dynamically attach per-event data to FlightPos objects
  var amendments = List.empty[Any]

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
    f"$id%-7s $hh%02d:$mm%02d:$ss%02d ${altitude.toFeet.toInt}%6dft ${heading.toNormalizedDegrees.toInt}%3d° ${speed.toKnots.toInt}%4dkn"
  }
}