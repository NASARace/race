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

import java.awt.{Color, Font}

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, DelayedAction}
import gov.nasa.race.core._
import gov.nasa.race.swing.AkkaSwingBridge
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.worldwind.layers.RenderableLayer
import gov.nasa.worldwind.render.Material

import scala.concurrent.duration.FiniteDuration

/**
  * a RenderableLayer with RaceLayerInfo fields
  *
  * this is the abstract base of all RACE specific WorldWind layers. Instances
  * are initialized through their own Config objects
  *
  * Note - this class provides the abstract members of the
  * RaceLayerInfo trait that can be initialized from the ctor Config argument
  */
trait RaceLayer extends RenderableLayer with RaceLayerInfo {
  val raceViewer: RaceViewer
  val config: Config

  val name = config.getString("name")
  val readFrom = config.getOptionalStringList("read-from")
  val categories = Set(config.getStringListOrElse("categories", Seq("data")): _*)
  val description = config.getStringOrElse("description", "RACE layer")

  val enable = config.getBooleanOrElse("enable", true)
  val enablePick = true

  setName(name)
  ifSome(config.getOptionalDouble("min-altitude")){setMinActiveAltitude}
  ifSome(config.getOptionalDouble("max-altitude")){setMaxActiveAltitude}

  /**
    * lazy init once we have a wwd and redrawManager
    * NOTE - don't forget to call super.initializeLayer when overriding
    */
  override def initializeLayer: Unit = {
    super.initializeLayer

    // set a select handler
    onSelected { e =>
      e.getTopObject match {
        case o:RaceLayerPickable => if (o.layer == this) selectObject(o, getEventAction(e))
        case other =>
      }
    }
  }

  //--- object attribute change

  def changeObject(objectId: String, action: LayerObjectAction) = {}

  // called in response to a WW SelectHandler notification if object is interactively picked
  // NOTE this is only called for the layer that owns the picked renderable
  def selectObject(o:RaceLayerPickable, a:EventAction): Unit = {} // default is do nothing

  // called by RaceViewer if a focus object is reset
  def setFocused (e: LayerObject, isFocused: Boolean, report: Boolean): Unit = {} // default is no focus objects
}

/**
  * a RaceLayer with configurable rendering attributes.
  * Override the respective val if the raceView defaults don't fit
  */
trait ConfigurableRenderingLayer extends RaceLayer {

  def defaultColor: Color = raceViewer.defaultColor
  val color = config.getColorOrElse("color", defaultColor)

  def defaultLabelColor: Color = color
  val labelColor = config.getColorOrElse("label-color", defaultLabelColor)

  def defaultLineColor: Color = color
  val lineColor = config.getColorOrElse("line-color", defaultLineColor)

  val labelMaterial = new Material(labelColor)
  val lineMaterial = new Material(lineColor)

  def defaultLabelFont: Font = raceViewer.defaultLabelFont
  val labelFont = config.getFontOrElse("label-font", defaultLabelFont)

  def defaultSubLabelFont: Font = labelFont
  val subLabelFont = config.getFontOrElse("sublabel-font", defaultSubLabelFont)
}

/**
  * a RaceLayer that processes messages received from the bus and hence has
  * an actor it is associated with.
  * Note that we don't extend RaceActorLayer directly since that would break the
  * fundamental actor guarantee of not executing actor code in two overlapping threads.
  * RaceLayerActors execute in Akka threads, whereas RaceLayers executes in the
  * AWT EventDispatchThread. The context switch boundaries should be explicitly
  * visible in the associated types
  */
trait SubscribingRaceLayer extends RaceLayer with AkkaSwingBridge {
  val actor: RaceLayerActor = raceViewer.createActor(name){ createLayerActor }

  var updateCount = 0// to keep track of number of changes
  def incUpdateCount: Unit = updateCount += 1
  def size: Int // answer number of items in layer

  /**
    * override if we need a layer specific actor (e.g. for actor<->layer communication)
    * NOTE while this allows to use nested class actors, care must be taken to avoid
    * race conditions between the actor and their encapsulating layer. Also note this
    * gets executed before the concrete layer ctor hence layer fields are not available yet
    */
  def createLayerActor: RaceLayerActor =  new RaceLayerActor(config,this)

  // override this if the layer actor should subscribe to more than the "read-from" channels
  def extraReadChannels: Seq[String] = Seq.empty[String]

  // forwards (?? thread safety)
  def request (channel: String, topic: Topic) = actor.request(channel, topic)
  def requestTopic (topic: Topic) = actor.requestTopic(topic)
  def release (channel: String, topic: Topic) = actor.release(channel, topic)
  def releaseTopic (topic: Topic) = actor.releaseTopic(topic)
  def releaseAll = actor.releaseAll

  def debug(f: => String) = gov.nasa.race.core.debug(f)(actor.log)
  def info(f: => String) = gov.nasa.race.core.info(f)(actor.log)
  def warning(f: => String) = gov.nasa.race.core.warning(f)(actor.log)
  def error(f: => String) = gov.nasa.race.core.error(f)(actor.log)

  def delay (t: FiniteDuration, f: ()=>Unit): Unit = actor.delay(t,f)
}


/**
  * a RaceActor that is associated with a SubscribingRaceLayer.
  * Note that the actor executes in Akka threads whereas the layer is executed in
  * the AWT EventDispatcher, hence care must be taken to avoid race conditions
  */
class RaceLayerActor (val config: Config, val layer: SubscribingRaceLayer) extends ChannelTopicSubscriber {
  info(s"created RaceLayerActor '${layer.name}'")
  val extraChannels = layer.extraReadChannels

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config) = {
    info(s"initializing RaceLayerActor '${layer.name}'")
    extraChannels.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onInitializeRaceActor(rc, layer.config)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    extraChannels.foreach { channel => busFor(channel).unsubscribe(self,channel)}
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case msg: BusEvent => layer.queueMessage(msg)
    case msg: DelayedAction => layer.queueMessage(msg)
  }
}

/**
  * a Renderable that is pick enabled and associated with a RaceLayer
  */
trait RaceLayerPickable {
  def layer: RaceLayer
  def layerItem: AnyRef
}
