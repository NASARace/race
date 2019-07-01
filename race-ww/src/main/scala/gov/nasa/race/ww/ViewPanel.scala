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
import gov.nasa.race._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing._
import gov.nasa.race.uom.Length
import gov.nasa.race.ww.LayerObjectAction.LayerObjectAction
import gov.nasa.worldwind.event.{PositionEvent, PositionListener}
import gov.nasa.worldwind.globes.projections.ProjectionMercator
import gov.nasa.worldwind.globes.{EarthFlat, FlatGlobe, Globe}

import scala.language.postfixOps
import scala.swing._
import scala.swing.event.ButtonClicked

/**
  * WorldWind panel to set the world type (globe, flat(projection))
  * and show eye position
  */
class ViewPanel (raceViewer: RaceViewer, config: Option[Config]=None) extends GBPanel
                  with RacePanel with PositionListener with ViewListener with LayerObjectListener {

  val wwd = raceViewer.wwd

  /////// we don't have a initialized wwd.view yet
  val earthGlobe: Globe = wwd.getModel().getGlobe()
  val earthFlat: FlatGlobe = new EarthFlat(earthGlobe.getElevationModel())
  earthFlat.setProjection(new ProjectionMercator)

  //--- buttons to switch between globe displays
  val globeBtn = new RadioButton("globe") {selected = true}.styled()
  val flatBtn = new RadioButton("flat map").styled()
  val group = new ButtonGroup( globeBtn,flatBtn)

  val focusBtn = new CheckBox("focus").styled()
  focusBtn.enabled = false

  //--- fields to display attitude information
  val valLen = 11
  val lblLen = 10
  val lonField = new DoubleOutputField("lon", "%+3.5f", valLen,lblLen).styled()
  val latField = new DoubleOutputField("lat", "%+3.5f", valLen,lblLen).styled()
  val altField = new DoubleOutputField("alt [ft]", "%6.0f", valLen,lblLen).styled()
  val elevField = new DoubleOutputField("elev [ft]", "%6.0f", valLen,lblLen).styled()

  val c = new Constraints( fill=Fill.Horizontal)
  layout(new FlowPanel(globeBtn,flatBtn,focusBtn).styled()) = c(0,0).gridwidth(2).anchor(Anchor.West)
  layout(latField)  = c(0,1).weightx(0.5).anchor(Anchor.East).gridwidth(1)
  layout(lonField)  = c(1,1)
  layout(altField)  = c(0,2)
  layout(elevField) = c(1,2)

  //--- reactions
  listenTo(globeBtn, flatBtn, focusBtn)
  reactions += {
    case ButtonClicked(`globeBtn`) =>
      wwd.getView.stopMovement()
      wwd.getModel.setGlobe(earthGlobe)

    case ButtonClicked(`flatBtn`) =>
      wwd.getView.stopMovement()
      wwd.getModel.setGlobe(earthFlat)

    case ButtonClicked(`focusBtn`) =>
      raceViewer.resetFocused
  }

  wwd.addPositionListener(this)

  override def moved (e: PositionEvent): Unit = {
    ifNotNull(e.getPosition){ pos =>
      latField.setValue(pos.getLatitude.getDegrees)
      lonField.setValue(pos.getLongitude.getDegrees)
      val elev = Length.meters2Feet(wwd.getModel.getGlobe.getElevation(pos.getLatitude, pos.getLongitude))
      elevField.setValue(elev)
      // this shows the cursor location, not the eye pos, hence no altitude update
    }
  }

  raceViewer.addViewListener(this)
  raceViewer.addObjectListener(this)

  override def viewChanged (viewGoal: ViewGoal)= {
    val alt = Length.meters2Feet(viewGoal.zoom)
    altField.setValue(alt)
  }

  override def objectChanged (obj: LayerObject, action: LayerObjectAction) = {
    action match {
      case LayerObjectAction.StopFocus =>
        focusBtn.selected = false
        focusBtn.enabled = false
      case LayerObjectAction.StartFocus =>
        focusBtn.selected = true
        focusBtn.enabled = true
      case _ => // ignore
    }
  }

}
