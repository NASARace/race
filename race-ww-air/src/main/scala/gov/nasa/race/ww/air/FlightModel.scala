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
package gov.nasa.race.ww.air

import gov.nasa.race.ww._
import gov.nasa.race.air.InFlightAircraft
import gov.nasa.race.util.StringUtils
import gov.nasa.worldwind.render.DrawContext
import osm.map.worldwind.gl.obj.ObjRenderable

import scala.util.matching.Regex

/**
  * Renderable representing 3D aircraft model
  */
class FlightModel[T <: InFlightAircraft](pattern: String, src: String, size: Double) extends ObjRenderable(FarAway,src) {

  val regex = StringUtils.globToRegex(pattern)
  setSize(size)


  def matches (spec: String) = StringUtils.matches(spec,regex)

  def update (newT: T) = {
    setPosition(newT)
  }
}
