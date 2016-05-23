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

package gov.nasa.race.ww

import com.typesafe.config.Config
import gov.nasa.race.common._
import gov.nasa.race.swing.{AkkaSwingBridge, DoubleOutputField}
import gov.nasa.race.swing.Style._
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.globes.projections.ProjectionMercator
import gov.nasa.worldwind.globes.{Earth, EarthFlat}
import gov.nasa.worldwind.terrain.ZeroElevationModel

import scala.language.postfixOps
import scala.swing._
import scala.swing.event.ButtonClicked

/**
 * WorldWind panel to set the world type (globe, flat(projection))
 */
class ViewPanel (raceView: RaceView, config: Option[Config]=None) extends BoxPanel(Orientation.Vertical)
                  with DeferredPositionListener with EyePosListener {

  val wwd = raceView.wwd
  val earthGlobe = new Earth
  val earthFlat = new EarthFlat
  earthFlat.setElevationModel(new ZeroElevationModel)
  earthFlat.setProjection(new ProjectionMercator)

  //--- buttons to switch between globe displays
  val globeButton = new RadioButton("globe") {selected = true} styled()
  val flatButton = new RadioButton("flat").styled()
  val group = new ButtonGroup( globeButton,flatButton)

  listenTo(globeButton, flatButton)
  reactions += {
    case ButtonClicked(`globeButton`) =>
      wwd.getModel.setGlobe(earthGlobe)
      wwd.getView.stopMovement()

    case ButtonClicked(`flatButton`) =>
      wwd.getModel.setGlobe(earthFlat)
      wwd.getView.stopMovement()
  }

  //--- fields to display attitude information
  val lonField = new DoubleOutputField("lon", "%+3.5f").styled()
  val latField = new DoubleOutputField("lat", "%+3.5f").styled()
  val altField = new DoubleOutputField("alt", "%,6.0f").styled()
  val elevField = new DoubleOutputField("elev", "%,6.0f").styled()

  contents += new FlowPanel(globeButton,flatButton).styled()
  contents += new GridPanel(2,2) {
    contents ++= Seq(lonField, latField, altField, elevField)
  } styled()

  onMoved(wwd){ positionEvent =>
    ifNotNull(positionEvent.getPosition){ pos =>
      latField.setValue(pos.getLatitude.getDegrees)
      lonField.setValue(pos.getLongitude.getDegrees)
      elevField.setValue(wwd.getModel.getGlobe.getElevation(pos.getLatitude, pos.getLongitude))
    }
  }

  raceView.addEyePosListener(this)
  def eyePosChanged(eyePos:Position, animHint: String) {
    altField.setValue(eyePos.getElevation)  // getAltitude ?
  }
}
