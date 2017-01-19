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
package gov.nasa.race.ww.air

import com.typesafe.config.Config
import gov.nasa.race.air.InFlightAircraft
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.VarEuclideanDistanceFilter3D
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.{EyePosListener, Models, RaceView}
import gov.nasa.worldwind.geom.Position

import scala.concurrent.duration._

/**
  * a trait that mixes in 3D model display
  * This entails three tasks:
  *
  * (1) loading and accessing a collection of 3D models that can be associated with flights.
  * Since 3D models are quite expensive, the association can be optional, i.e. not every flight
  * might have a 3D model
  *
  * (2) maintaining a list of InFlightAircraft objects that are close enough to the eye position
  * to warrant a 3D model display. 3D models are scaled with respect to eyepos distance, i.e. they
  * cannot be used in lieu of flightSymbols (which are fixed in size and attitude). This list
  * changes because of view changes *and* flight position changes. The former requires a bulk
  * computation (over all known flights), and therefore has to be aware of animations (i.e. transient
  * eye position changes)
  *
  * (3) proper update of FlightEntry renderables based on visibility of respective InFlightAircraft
  * objects
  */
trait FlightModelClient[T <:InFlightAircraft] extends EyePosListener  {

  //--- to be provided by concrete type
  val config: Config
  val raceView: RaceView

  // this is used to obtain our proximity list without having to know the details of how
  // the concrete type stores active FlightEntries
  def matchingFlights (f: (FlightEntry[T])=>Boolean): Seq[FlightEntry[T]]
  def delay (t: FiniteDuration, f: ()=>Unit)


  //--- 3D models and proximities
  val eyeDistanceFilter = new VarEuclideanDistanceFilter3D(Angle0,Angle0,Length0, Meters(config.getDoubleOrElse("model-distance",2000.0)))
  var proximities = Seq.empty[FlightEntry[T]]
  val models = new Models(config.getConfigSeq("models"))

  if (models.nonEmpty) {
    raceView.addEyePosListener(this)  // will update lastEyePos and proximities
  }

  def getModel (e: FlightEntry[T]): Option[AircraftModel[T]] = {
    models.get(e.obj.cs).map(cr => new AircraftModel[T](e, cr))
  }

  private var pendingUpdates = 0

  def eyePosChanged(eyePos: Position, animHint: String): Unit = {
    pendingUpdates += 1
      delay(1.second, () => {
      if (models.nonEmpty) {
        eyeDistanceFilter.updateReference(Degrees(eyePos.latitude.degrees), Degrees(eyePos.longitude.degrees), Meters(eyePos.getAltitude))

        pendingUpdates -= 1
        if (pendingUpdates == 0) {
          val newProximities = calculateProximities
          proximities = newProximities
          //println(s"@@ $proximities")
        }
      }
    })
  }

  def calculateProximities = {
    matchingFlights( e => {
      val fpos = e.obj
      val pos = fpos.position
      eyeDistanceFilter.pass(pos.φ,pos.λ,fpos.altitude)
    })
  }

}
