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

import gov.nasa.race.geo.GeoPositioned3D
import gov.nasa.race.uom.{Angle, Speed}

/**
  * common types for track objects
  *
  * this package relies on gov.nasa.race.geo for spatial information and adds time and identification
  * or generic tracks
  */
package object track {

  /** a GeoPosition3D that is associated with a date */
  trait TrackPoint extends GeoPositioned3D with Dated

  trait IdentifiableTrackPoint extends TrackPoint with IdentifiableObject

  trait MovingObject {
    def heading: Angle
    def speed: Speed
    def vr: Speed   // vertical rate
  }

}
