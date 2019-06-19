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

package gov.nasa.race.swing

/**
  * a Swing panel with start/pause/stop run controls and corresponding
  * state management
  */

import java.awt.{Color,Dimension}
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.swing._
import scala.swing.event.ButtonClicked

object RunControlPanel {
  object Status extends Enumeration {
    type Status = Value
    val Disabled, Runnable, Running, Paused, Stopped = Value
  }

  def main (args:Array[String]): Unit = {
    val top = new MainFrame {
      title = "RunControlPanel Test"
      contents = new RunControlPanel().styled("topLevel")
    }
    top.open()
  }
}
import RunControlPanel.Status._

class RunControlPanel extends GBPanel {
  var status = Disabled

  var startAction: ()=>Boolean = ()=> true
  var pauseAction: ()=>Boolean = ()=> true
  var resumeAction: ()=>Boolean = ()=> true
  var stopAction: ()=>Boolean = ()=> true

  val runButton = new ToggleButton {
    icon = Swing.Icon(getClass.getResource("Step-Forward-Hot-icon-48.png"))
    pressedIcon = Swing.Icon(getClass.getResource("Step-Forward-Pressed-icon-48.png"))
    selectedIcon = Swing.Icon(getClass.getResource("Step-Forward-Normal-Yellow-icon-48.png"))
    disabledIcon = Swing.Icon(getClass.getResource("Step-Forward-Disabled-icon-48.png"))
    borderPainted = false
  }
  val stopButton = new ToggleButton {
    icon = Swing.Icon(getClass.getResource("Stop-Normal-Red-icon-48.png"))
    disabledIcon = Swing.Icon(getClass.getResource("Stop-Disabled-icon-48.png"))
    borderPainted = false
  }
  val statusOutput = new StringOutput("status: ")(6).styled()

  val c = new Constraints( gridy=0, fill=Fill.Horizontal, anchor=Anchor.West, insets=(2,2,2,2), weightx=0)
  layout(runButton) = c
  layout(stopButton) = c
  layout(statusOutput) = c

  listenTo(runButton)
  listenTo(stopButton)

  reactions += {
    case ButtonClicked(`runButton`) =>
      if (runButton.selected) setStatus(Running)
      else setStatus(Paused)
    case ButtonClicked(`stopButton`) => setStatus(Stopped)
  }


  def onStart (a: =>Boolean) = startAction = () => a
  def onPause (a: =>Boolean) = pauseAction = () => a
  def onResume (a: =>Boolean) = resumeAction = () => a
  def onStop (a: =>Boolean) = stopAction = () => a

  def setStatus (newStatus: Status) = {
    (status,newStatus) match {
      case (Disabled,Runnable) =>
        runButton.enabled = true
        stopButton.enabled = false
      case (Runnable,Running) | (Stopped,Running) =>
        if (startAction()) {
          stopButton.enabled = true
        }
      case (_,Paused) =>
        pauseAction()
      case (Paused,Running) =>
        resumeAction()
      case (_,Stopped) =>
        if (stopAction()) {
          runButton.selected = false
          runButton.enabled = true
          stopButton.enabled = false
        }
      case (Disabled,Disabled) | (Runnable,Disabled) =>
        runButton.enabled = false
        stopButton.enabled = false
      case other => // ignore (esp. disable while running or paused)
    }

    status = newStatus
    statusOutput.setValue(newStatus.toString)
    statusOutput.setForeground(statusColor(newStatus))
  }

  def statusColor (s: Status) = {
    s match {
      case Disabled => Color.lightGray
      case Runnable => Color.white
      case Running => Color.green
      case Paused => Color.yellow
      case Stopped => Color.red
      case other => Color.lightGray
    }
  }

  def setHot = setStatus(Runnable)
  def setDisabled = setStatus(Disabled)
  def setStopped = setStatus(Stopped)

}
