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
import java.awt.image.BufferedImage
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO
import akka.actor.{ActorRef, Props}
import com.typesafe.config.Config
import gov.nasa.race.common.ConfigUtils._
import gov.nasa.race.common._
import gov.nasa.race.core.{ContinuousTimeRaceActor, RaceContext, _}
import gov.nasa.race.swing.Redrawable
import gov.nasa.race.swing.Style._
import gov.nasa.worldwind.Configuration
import gov.nasa.worldwind.geom.Position
import gov.nasa.worldwind.layers.Layer
import scala.collection.JavaConversions._
import scala.collection.immutable.ListMap
import scala.reflect.ClassTag
import scala.swing.Component




/**
  * the master actor for geospatial display of RACE data channels,
  * using NASA WorldWind for the heavy lifting
  *
  * Each layer is instantiated from the config, each SuscribingRaceLayer
  * has an associated actor which is created/supervised by this RaceViewer
  * instance
  */
class RaceViewerActor(val config: Config) extends ContinuousTimeRaceActor
                                   with SubscribingRaceActor with PublishingRaceActor {
  var actors = List.empty[ActorRef] // to be populated during view construction
  val view = new RaceView(this) // our abstract interface towards the layers and the UI side

  //--- RaceActor callbacks

  override def initializeRaceActor(rc: RaceContext, actorConf: Config): Unit = {
    super.initializeRaceActor(rc, actorConf)
    initDependentRaceActors(actors, rc, actorConf)
  }

  override def reInitializeRaceActor(rc: RaceContext, actorConf: Config): Any = {
    super.reInitializeRaceActor(rc,actorConf)
    initDependentRaceActors(actors, rc, actorConf)
  }

  override def startRaceActor(originator: ActorRef) = {
    super.startRaceActor(originator)
    startDependentRaceActors(actors)
  }

  override def terminateRaceActor (originator: ActorRef) = {
    super.terminateRaceActor(originator)

    info(s"${name} terminating")
    terminateDependentRaceActors(actors)

    if (view.displayable) {
      info(s"${name} closing WorldWind window..")
      view.close
      info(s"${name} WorldWind window closed")
    } else {
      info(s"${name} WorldWind window already closed")
    }
  }

  //--- supporting functions

  def addActor (actorRef: ActorRef) = actors = actorRef :: actors
}


object RaceView {

  //--- eye position animation hints
  final val CenterClick = "CenterClick"
  final val CenterDrag = "CenterDrag"
  final val Zoom = "Zoom"
  final val Pan = "Pan"
  final val Goto = "Goto"  // the catch all
}

/**
  * this is a viewer state facade we pass down into our components, which
  * are (mostly) executing in the UI thread(s) and hence should not be able
  * to directly access our actor internals.
  *
  * This follows the same approach as RaceLayer/RaceLayerActor to map thread
  * boundaries to types. It also acts as a mediator/broker
  *
  * NOTE this class has to be thread-aware, don't introduce race conditions
  * by exposing objects.
  * The ctor still executes in the actor thread, the other methods (mostly)
  * from the EDT
  */
class RaceView (viewerActor: RaceViewerActor) extends DeferredEyePositionListener {
  implicit val log = viewerActor.log

  Configuration.setValue("gov.nasa.worldwind.avkey.ViewInputHandlerClassName", classOf[RaceViewInputHandler].getName)
  val gotoTime = config.getIntOrElse("goto-time", 4000)

  val layers = createLayers

  // we want to avoid several DeferredXListeners because of the context switch overhead
  // hence we have a secondary listener level here
  var eyePosListeners = List[EyePosListener]()
  def addEyePosListener (newListener: EyePosListener) = eyePosListeners = newListener :: eyePosListeners
  def removeEyePosListener(listener: EyePosListener) = eyePosListeners = eyePosListeners.filter(_.ne(listener))

  var layerListeners = List[LayerListener]()
  def addLayerListener (newListener: LayerListener) = layerListeners = newListener :: layerListeners
  def removeLayerListener (listener: LayerListener) = layerListeners = layerListeners.filter(_.ne(listener))

  val frame = new WorldWindFrame(viewerActor.config, this)
  val redrawManager = RedrawManager(wwd.asInstanceOf[Redrawable]) // the shared one, layers/panels can have their own
  val inputHandler = wwdView.getViewInputHandler.asInstanceOf[RaceViewInputHandler]
  var layerController: Option[LayerController] = None

  val panels: ListMap[String,PanelEntry] = createPanels
  panels.foreach{ e => frame.initializePanel(e._2) }

  // this has to be deferred because WWJs setViewInputHandler() does not initialize properly
  ifInstanceOf[RaceViewInputHandler](wwd.getView.getViewInputHandler) {_.attachToRaceView(this)}

  frame.open

  //---- end initialization, from here on we need to be thread safe

  def config = viewerActor.config
  def displayable = frame.displayable
  def simClock = viewerActor.simClock
  def close = frame.close

  // WWD accessors
  def wwd = frame.wwd
  def wwdView = frame.wwd.getView
  def eyePosition = frame.wwd.getView.getEyePosition

  def createLayers = {
    config.getOptionalConfigList("layers").foldLeft(Seq.empty[RaceLayer]){ (seq,layerConfig) =>
      val layerName = layerConfig.getString("name")
      val layerClsName = layerConfig.getString("class")
      info(s"creating layer '$layerName': $layerClsName")
      val layer = newInstance[RaceLayer](layerClsName, Array(classOf[RaceView], classOf[Config]), Array(this, layerConfig))
      if (layer.isDefined){
        seq :+ layer.get
      } else {
        error(s"layer $layerName did not instantiate")
        seq
      }
    }
  }

  /**
    * this method does what Akka tries to avoid - making the actor object
    * available in the caller context. Use with extreme care, and only use the
    * returned actor reference for thread safe operations
    * The reason why we provide this function is that we have a number of
    * constructs (such as layers or panels) that consist of a pair of an actor
    * and a Swing/WorldWind object. Such pairs are subject to context switches,
    * i.e. are inherently dangerous with respect to threading. While we do provide
    * helper constructs such as AkkaSwingBridge, there is no general Swing-to-Akka
    * interface other than sending messages. If we need to query actor field values
    * from Swing this would require ask patterns, which carries a significant
    * performance penalty
    */
  def createActor[T<:RaceActor :ClassTag](name: String)(f: => T): T = {
    val semaphore = new Semaphore(0)
    var actor: T = null.asInstanceOf[T]
    def instantiateActor: T = {
      actor = f            // (1)
      semaphore.release()
      actor
    }
    val actorRef = viewerActor.context.actorOf(Props(instantiateActor),name)
    viewerActor.addActor(actorRef)
    semaphore.acquire()    // block until (1) got executed
    actor
  }

  def createPanels: ListMap[String,PanelEntry] = {
    if (config.hasPath("panels")) createPanels( config.getOptionalConfigList("panels"))
    else createDefaultPanels
  }

  def createPanels (panelConfigs: Seq[Config]): ListMap[String,PanelEntry] = {
    panelConfigs.foldLeft(ListMap.empty[String,PanelEntry]) { (map,panelConfig) =>
      try {
        val name = panelConfig.getString("name")
        val tooltip = panelConfig.getStringOrElse("tooltip", "click to hide/show panel")
        val expand = panelConfig.getBooleanOrElse("expand", true)

        info(s"creating console panel $name")
        val panel = newInstance[Component](panelConfig.getString("class"),
          Array(classOf[RaceView], classOf[Option[Config]]),
          Array(this, Some(panelConfig))).get
        panel.styled('consolePanel)
        map + (name -> PanelEntry(name, panel, tooltip, expand))
      } catch {
        case t: Throwable => error(s"exception creating panel: $t"); map
      }
    }
  }

  def createDefaultPanels: ListMap[String,PanelEntry] = {
    val collapsed = config.getOptionalStringList("collapse-panels").toSet
    def panelEntry(name: String, c: Component, tt: String) = name -> PanelEntry(name,c,tt,!collapsed.contains(name))
    def styled (c: Component) = c.styled('consolePanel)

    ListMap(
      panelEntry("clock", styled(new BasicClockPanel(this)), "click to hide/show clocks"),
      panelEntry("view", styled(new ViewPanel(this)), "click to hide/show viewing parameters"),
      panelEntry("sync", styled(new SyncPanel(this)), "click to hide/show view synchronization"),
      panelEntry("layers", styled(new LayerListPanel(this)), "click to hide/show layer list"),
      panelEntry("selected layer", styled(new EmptyPanel(this)), "click to hide/show selected layer"),
      panelEntry("selected object", styled(new EmptyPanel(this)), "click to hide/show selected object")
    )
  }

  def showConsolePanels(setVisible: Boolean) = frame.showConsolePanels(setVisible)
  def showConsolePanel(name: String, setVisible: Boolean) = frame.showConsolePanel(name, setVisible)

  // standard dynamic panels
  def setLayerPanel (c: Component) = if (panels.containsKey("selected layer")) frame.setPanel("selected layer", c)
  def setObjectPanel (c: Component) = if (panels.containsKey("selected object")) frame.setPanel("selected object", c)


  // we need this here because of universe specific loaders
  def newInstance[T: ClassTag] (clsName: String,
                                argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T] = {
    viewerActor.newInstance(clsName,argTypes,args)
  }

  // called by RaceViewInputHandler
  def newTargetEyePosition (eyePos: Position, animationHint: String) = eyePosListeners.foreach(_.eyePosChanged(eyePos,animationHint))

  def setLastUserInput = inputHandler.setLastUserInput
  def millisSinceLastUserInput = inputHandler.millisSinceLastUserInput

  //--- view (eye position) transitions
  def centerOn (pos: Position) = {
    inputHandler.stopAnimators
    inputHandler.addCenterAnimator(eyePosition, pos, true)
  }
  def zoomTo (zoom: Double) = {
    inputHandler.stopAnimators
    inputHandler.addZoomAnimator(eyePosition.getAltitude, zoom)
  }
  def panTo (pos: Position, zoom: Double) = {
    inputHandler.stopAnimators
    inputHandler.addPanToAnimator(pos,ZeroWWAngle,ZeroWWAngle,zoom,true)
  }
  def setEyePosition (pos: Position, animTime: Long) = {
    inputHandler.stopAnimators
    inputHandler.addEyePositionAnimator(animTime,eyePosition,pos)
  }

  //--- layer management
  def setLayerController (controller: LayerController) = layerController = Some(controller)
  def layerChanged (layer: Layer) = layerListeners.foreach(_.layerChanged(layer))
  def changeLayer (name: String, enable: Boolean) = layerController.foreach(_.changeLayer(name,enable))

  def configuredLayerCategories(default: Set[String]): Set[String] = {
    if (config.hasPath("layer-categories")) config.getStringList("layer-categories").toSet else default
  }

  def redraw = redrawManager.redraw()
  def redrawNow = redrawManager.redrawNow()
}