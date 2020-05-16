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

import java.awt.Color

import com.typesafe.config.Config
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{DigitalClock, DigitalStopWatch, GBPanel, _}
import javax.swing.Box

import scala.swing.{Action, BoxPanel, Button, FlowPanel, Label, Orientation}

/**
 * basic clock panel with simulation and elapsed time
 */
class BasicClockPanel(raceView: RaceViewer, config: Option[Config]=None) extends GBPanel with RacePanel {

  val simClock = raceView.simClock
  val simClockPanel = new DigitalClock(Some(simClock)).styled()
  val simStopWatchPanel = new DigitalStopWatch(simClock).styled()

  val c = new Constraints( fill=Fill.Horizontal, anchor=Anchor.West, insets=(8,2,0,2))
  layout(simClockPanel)                           = c(0,0).weightx(0)
  layout(new Label("elapsed:").styled("labelFor")) = c(1,0).weightx(0.5)
  layout(simStopWatchPanel)                       = c(2,0).weightx(0)

}

class ControlClockPanel (raceView: RaceViewer, config: Option[Config]=None) extends BasicClockPanel(raceView,config) {

  val stopIcon = Images.getScaledIcon("stop-red-16x16.png")
  val playIcon = Images.getScaledIcon("play-green-16x16.png")
  val pauseIcon = Images.getScaledIcon("pause-yellow-16x16.png")
  def pauseResumeIcon = if (raceView.isPaused) playIcon else pauseIcon

  val stopButton = new Button(){
    action = Action(""){ raceView.requestRaceTermination }
    icon = stopIcon
  } .styled() // should be confirmed

  val pauseResumeButton = new Button(){
    action = Action(""){ raceView.requestPauseResume }
    icon = playIcon
  } .styled()

  val controls = new FlowPanel(FlowPanel.Alignment.Right)(stopButton,pauseResumeButton).styled()
  controls.vGap = 0

  layout(controls) = c(2,1).weightx(0)

  override def onRaceStarted = pauseResumeButton.icon = pauseIcon
  override def onRacePaused = pauseResumeButton.icon = playIcon
  override def onRaceResumed = pauseResumeButton.icon = pauseIcon
}
