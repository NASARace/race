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

package gov.nasa.race.ww.track

import gov.nasa.race.track.Tracked3dObject
import gov.nasa.race.ww._
import gov.nasa.worldwind.render.Offset

object TrackSymbol{
  val LabelOffset = Offset.fromFraction(1.1,0.5)
  val IconOffset = Offset.fromFraction(1.1,0.5)
}

/**
  * a LayerSymbol representing a TrackedObject
  */
class TrackSymbol[T <: Tracked3dObject](val trackEntry: TrackEntry[T]) extends LayerSymbol(trackEntry) {

  // override if there are concrete TrackedObject specifics which are display relevant
  def update (newT: T) = super.update

}
