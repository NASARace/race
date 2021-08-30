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
package gov.nasa.race

import gov.nasa.race.common.{AssocSeq, ClearableElementsHolder}
import gov.nasa.race.geo.GeoPositioned
import gov.nasa.race.uom.{Angle, Speed}

import scala.collection.mutable.ArrayBuffer

/**
  * common types for track objects
  *
  * this package relies on gov.nasa.race.geo for spatial information and adds time and identification
  * or generic tracks
  */
package object track {

  /** a GeoPositioned that is associated with a date */
  trait TrackPoint extends GeoPositioned with Dated

  trait IdentifiableTrackPoint extends TrackPoint with IdentifiableObject

  /**
    * object that is a moving 3D point, i.e. has a horizontal and vertical speed vector
    */
  trait MovingObject {
    def heading: Angle
    def speed: Speed
    def vr: Speed   // vertical rate
  }

  /**
    * non-point object that has an orientation relative to the horizontal plane
    * note that pitch and roll default to 0, i.e. have to be overridden if the concrete type sets them
    */
  trait AttitudeObject {
    def pitch: Angle = Angle.UndefinedAngle
    def roll: Angle = Angle.UndefinedAngle
  }

  class Velocity2D (
    var heading: Angle,
    var speed: Speed
  )


  /**
    * a mutable Seq of TrackedObjects that can be associated to a common src
    */
  class MutSrcTracks[T <: TrackedObject](initSize: Int) extends ArrayBuffer[T](initSize) with AssocSeq[T,String] {
    var src: String = null
    override def assoc: String = src
  }

  /**
    * an owner of a MutSrcTracks collection
    */
  trait MutSrcTracksHolder[T <: TrackedObject, U <: MutSrcTracks[T]] extends ClearableElementsHolder[U] {
    override def clearElements: Unit = {
      super.clearElements
      elements.src = null
    }

    //--- just some aliases to improve readability
    @inline def clearTracks = clearElements
    @inline def tracks = elements
  }
}
