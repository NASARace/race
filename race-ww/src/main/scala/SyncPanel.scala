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

import com.typesafe.config.{Config, ConfigFactory}
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.core._
import gov.nasa.race.core.{PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.swing.{AkkaSwingBridge, OnOffIndicator, SwingTimer}
import gov.nasa.race.swing.Style._
import gov.nasa.worldwind.geom.{Angle, Position}
import gov.nasa.worldwind.layers.Layer

import scala.concurrent.duration._
import scala.swing._
import scala.swing.Swing._
import scala.swing.event.{ButtonClicked, SelectionChanged}


/**
  * panel to control remote viewer synchronization
  */
class SyncPanel (raceView: RaceView, config: Option[Config]=None)
                                      extends BorderPanel with EyePosListener with LayerListener with AkkaSwingBridge {
  val actor: SyncActor = raceView.createActor("viewerSync") {
    new SyncActor(this,config.getOrElse(ConfigFactory.empty))
  }
  implicit val log = actor.log

  val syncChannels = raceView.config.getStringListOrElse("sync-channels", Seq("viewer/sync"))
  var syncChannel: Option[String] = None

  //--- configurable synchronization animation speed
  val syncGotoTime = raceView.config.getIntOrElse("sync-goto", 2000)
  val syncZoomTime = raceView.config.getIntOrElse("sync-zoom", 500)
  val syncCenterClickTime = raceView.config.getIntOrElse("sync-centerclick", 1000)
  val syncCenterDragTime = raceView.config.getIntOrElse("sync-centerdrag", 500)

  var sendEyePos = false
  var receiveEyePos = false
  var sendLayerChange = false
  var receiveLayerChange = false

  val channelCombo = new ComboBox(syncChannels).styled()
  val onOffBtn = new CheckBox("active").styled()
  val readyLED = new OnOffIndicator
  val channelGroup = new FlowPanel {
    contents += channelCombo
    contents += onOffBtn
    contents += readyLED
  } styled()

  val outEyePos = new CheckBox("eye position").styled()
  val outLayer = new CheckBox("layer").styled()
  val outGroup = new BoxPanel(Orientation.Vertical) {
    border = TitledBorder(EtchedBorder, "outbound")
    contents += outEyePos
    contents += outLayer
  } styled('titled)

  val inEyePos = new CheckBox("eye position").styled()
  val inLayer = new CheckBox("layer").styled()
  val inGroup = new BoxPanel(Orientation.Vertical) {
    border = TitledBorder(EtchedBorder, "inbound")
    contents += inEyePos
    contents += inLayer
  } styled('titled)

  layout(channelGroup) = BorderPanel.Position.North
  layout(outGroup) = BorderPanel.Position.East
  layout(inGroup) =  BorderPanel.Position.West

  //--- reactions
  listenTo(channelCombo.selection)
  listenTo(onOffBtn)
  listenTo(outEyePos)
  listenTo(inEyePos)
  listenTo(inLayer)
  listenTo(outLayer)

  reactions += {
    case ButtonClicked(`onOffBtn`) => setSyncChannel
    case SelectionChanged(`channelCombo`) => setSyncChannel
    case ButtonClicked(`outEyePos`) => sendEyePos = outEyePos.selected
    case ButtonClicked(`inEyePos`) => receiveEyePos = inEyePos.selected
    case ButtonClicked(`outLayer`) => sendLayerChange = outLayer.selected
    case ButtonClicked(`inLayer`) => receiveLayerChange = inLayer.selected
  }

  val outboundBlackout = 300 // max millis since last user input during which we send out eyePos changes
  val inboundBlackout = 1000 // min millis since last user input until we process external changes
  val readyTimer = new SwingTimer(2.seconds,false)
  readyTimer.whenExpired {
    if (syncChannel.isDefined) readyLED.on
  }

  raceView.addEyePosListener(this)
  raceView.addLayerListener(this)

  // this is called when our view has a changed eyePos. Only send it out
  // if there was recent user input (i.e. the user initiated the change)
  def eyePosChanged (eyePos: Position, animationHint: String) = {
    if (sendEyePos && raceView.millisSinceLastUserInput < outboundBlackout) {
      syncChannel.foreach { channel =>
        info(s"outbound eyepos: $eyePos")
        publish(new EyePositionChanged(eyePos,animationHint))
      }
    }
  }

  def layerChanged (layer: Layer) = {
    if (sendLayerChange) {
      info(s"outbound layer change: ${layer.getName} enabled: ${layer.isEnabled}")
      publish(LayerChanged(layer.getName, layer.isEnabled))
    }
  }

  def handleMessage = {
    case EyePositionChanged(lat,lon,alt,hint) => handleEyePosChanged(lat,lon,alt,hint)
    case LayerChanged(name,isEnabled) => handleLayerChanged(name,isEnabled)
  }

  // this is called when we receive an external EyePositionChanged message. Only
  // change the view eyePos if there was no recent user input
  def handleEyePosChanged (lat: Double, lon: Double, alt: Double, animationHint: String) = {
    if (receiveEyePos && raceView.millisSinceLastUserInput > inboundBlackout) { // otherwise user input has priority
      val pos = new Position(Angle.fromDegreesLatitude(lat), Angle.fromDegreesLongitude(lon), alt)
      info(s"inbound eyepos: $pos")
      // Note that we can't use the specific remote (source) animation because we might have changed the
      // eye position locally, so we have to do a generic setEyePosition/panTo
      animationHint match {
        case RaceView.Zoom => raceView.setEyePosition(pos, syncZoomTime)
        case RaceView.CenterClick => raceView.setEyePosition(pos, syncCenterClickTime)
        case RaceView.CenterDrag => raceView.setEyePosition(pos, syncCenterDragTime)
        case RaceView.Pan => raceView.panTo(pos,alt)
        case other => raceView.setEyePosition(pos, syncGotoTime)
      }
      readyLED.off
      readyTimer.start
    }
  }

  def handleLayerChanged (name: String, enable: Boolean) = {
    if (receiveLayerChange) {
      info(s"inbound layer change: '$name' enabled: $enable")
      raceView.changeLayer(name, enable)
    }
  }

  //--- functions
  def setSyncChannel = {
    if (onOffBtn.selected) {
      syncChannel.foreach(unsubscribeFromSyncChannel)
      val channel = channelCombo.selection.item
      subscribeToSyncChannel(channel)
      syncChannel = Some(channel)
      //readyLED.on
      readyTimer.start

    } else {
      syncChannel.foreach(unsubscribeFromSyncChannel)
      syncChannel = None
      readyLED.off
    }
  }

  // <2do> we might have to do this via messages, not sure if Akka internally syncs subscription
  def subscribeToSyncChannel (channel: String) = actor.subscribe(channel)
  def unsubscribeFromSyncChannel (channel: String) = actor.unsubscribe(channel)
  def publish (msg: Any) = syncChannel.foreach(actor.publish(_,msg))
}

class SyncActor (panel: SyncPanel, val config: Config)
                                 extends SubscribingRaceActor with PublishingRaceActor {
  info(s"creating SyncActor")

  override def handleMessage = {
    case BusEvent(_,msg,originator) =>
      if (originator != self) {
        info(s"$name received $msg from $originator")
        panel.queueMessage(msg)
      }

  }
}
