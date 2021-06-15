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
import java.io.File
import java.util.concurrent.Semaphore

import akka.actor.Props
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{RaceActor, error, info}
import gov.nasa.race.swing._
import gov.nasa.race.swing.Redrawable
import gov.nasa.race.swing.Style._
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.LayerObjectAction.LayerObjectAction
import gov.nasa.race.{ifInstanceOf, ifSome}
import gov.nasa.worldwind.geom.{Angle, Position}
import gov.nasa.worldwind.layers.Layer

import scala.jdk.CollectionConverters._
import scala.collection.immutable.ListMap
import scala.reflect.ClassTag
import scala.swing.Component

/**
  * this is a viewer state facade we pass down into our components, which
  * are executing in the UI thread(s) and hence should not be able
  * to directly access our actor internals.
  *
  * This follows the same approach as RaceLayer/RaceLayerActor to map thread
  * boundaries to types. It also acts as a mediator/broker
  *
  * This is *not* a WW View
  *
  * NOTE this class has to be thread-aware, don't introduce race conditions
  * by exposing objects.
  *
  * Both ctor and methods are supposed to be executed from the UI thread
  */
class RaceViewer(viewerActor: RaceViewerActor) extends DeferredEyePositionListener {
  implicit val log = viewerActor.log
  def initTimedOut = viewerActor.initTimedOut

  setWorldWindConfiguration // NOTE - this has to happen before we load any WorldWind classes
  ifSome(config.getOptionalString("cache-dir")){ d => ConfigurableWriteCache.setRoot(new File(d)) }

  val zoomDistance = config.getIntOrElse("zoom-distance", 1000) // in [m]

  //--- animation parameters
  val gotoTime = config.getIntOrElse("goto-time", 2500)

  //--- defaults for configurable render attributes
  val defaultColor = config.getColorOrElse("color", Color.yellow)
  val defaultLabelColor = config.getColorOrElse("label-color", defaultColor)
  val defaultLineColor = config.getColorOrElse("line-color", defaultColor)
  val defaultLabelFont = config.getFontOrElse("label-font",  new Font(null,Font.PLAIN,scaledSize(13)))
  val defaultSubLabelFont = config.getFontOrElse("sublabel-font", defaultLabelFont)

  // we want to avoid several DeferredXListeners because of the context switch overhead
  // hence we have a secondary listener level here
  var viewListeners = List.empty[ViewListener]
  def addViewListener (newListener: ViewListener) = viewListeners = newListener :: viewListeners
  def removeViewListener(listener: ViewListener) = viewListeners = viewListeners.filter(_.ne(listener))

  var layerListeners = List.empty[LayerListener]
  def addLayerListener (newListener: LayerListener) = layerListeners = newListener :: layerListeners
  def removeLayerListener (listener: LayerListener) = layerListeners = layerListeners.filter(_.ne(listener))

  var objectListener = List.empty[LayerObjectListener]
  def addObjectListener (newListener: LayerObjectListener) = objectListener = newListener :: objectListener
  def removeObjectListener (listener: LayerObjectListener) = objectListener = objectListener.filter(_.ne(listener))

  val layers = createLayers
  val frame = new WorldWindFrame(viewerActor.config, this) // this creates the wwd instance

  val wwd = frame.wwd
  val wwdView = wwd.getView.asInstanceOf[RaceWWView]

  val redrawManager = RedrawManager(wwd.asInstanceOf[Redrawable]) // the shared one, layers/panels can have their own
  val viewController = wwdView.getViewInputHandler.asInstanceOf[RaceWWViewController]
  var layerController: Option[LayerController] = None

  val emptyLayerInfoPanel = new EmptyPanel(this)
  val emptyObjectPanel = new EmptyPanel(this)

  // a global, optional reference to single "focus object" which is set from/shared between layers
  var focusObject: Option[LayerObject] = None

  val panels: ListMap[String,PanelEntry] = createPanels

  // link configured WW objects to this RaceView
  ifInstanceOf[RaceWWView](wwdView){ _.attachToRaceView(this)}
  ifInstanceOf[RaceWWViewController](wwdView.getViewInputHandler){_.attachToRaceView(this)}

  if (!initTimedOut){
    frame.open()
    viewController.initialize
    panels.foreach{ e => frame.initializePanel(e._2) }
  } else {
    frame.dispose()
  }

  //---- end initialization, from here on we need to be thread safe

  def config = viewerActor.config
  def displayable = frame.displayable
  def simClock = viewerActor.simClock
  def updatedSimTime = viewerActor.updatedSimTime
  def close = frame.close()

  // WWD accessors
  def eyePosition = wwdView.getEyePosition
  def eyeLatLonPos = latLon2LatLonPos(wwdView.getEyePosition)
  def eyeAltitude = Meters(wwdView.getZoom)

  def viewPitch = wwdView.getPitch
  def viewHeading = wwdView.getHeading
  def viewRoll = wwdView.getRoll
  def isOrthgonalView = wwdView.isOrthgonalView

  def setWorldWindConfiguration = {
    // we use our own app config document which takes precedence over Worldwind's config/worldwind.xml
    // note that we also provide a separate config/worldwind.layers.xml (which is referenced from worldwind.xml)
    System.setProperty("gov.nasa.worldwind.app.config.document", "config/race-worldwind.xml")
  }

  def createLayers = {
    config.getOptionalConfigList("layers").foldLeft(Seq.empty[RaceLayer]){ (seq,layerConfig) =>
      val layerName = layerConfig.getString("name")
      val layerClsName = layerConfig.getString("class")
      info(s"creating layer '$layerName': $layerClsName")
      val layer = newInstance[RaceLayer](layerClsName, Array(classOf[RaceViewer], classOf[Config]), Array(this, layerConfig))
      if (layer.isDefined){
        seq :+ layer.get
      } else {
        error(s"layer $layerName did not instantiate")
        seq
      }
    }
  }

  def getLayer (name: String) = layers.find(l => l.name == name)

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
    val actorRef = viewerActor.actorOf(Props(instantiateActor),name)
    semaphore.acquire()    // block until (1) got executed
    viewerActor.addChildActorRef(actorRef,actor.config)
    actor
  }

  def createPanels: ListMap[String,PanelEntry] = {
    val collapsed = config.getOptionalStringList("collapse-panels").toSet

    if (config.hasPath("panels")) createPanels( config.getOptionalConfigList("panels"),collapsed)
    else createDefaultPanels(collapsed)
  }

  def createPanels (panelConfigs: Seq[Config], collapsed: Set[String]): ListMap[String,PanelEntry] = {
    panelConfigs.foldLeft(ListMap.empty[String,PanelEntry]) { (map,panelConfig) =>
      try {
        val panelEntry = createPanelEntry(panelConfig)
        if (collapsed.contains(panelEntry.name)) panelEntry.expand = false
        map + (panelEntry.name -> panelEntry)
      } catch {
        case t: Throwable => error(s"exception creating panel: $t"); map
      }
    }
  }

  def createPanelEntry (panelConfig: Config): PanelEntry = {
    val name = panelConfig.getString("name")
    val tooltip = panelConfig.getStringOrElse("tooltip", "click to hide/show panel")
    val expand = panelConfig.getBooleanOrElse("expand", true)

    info(s"creating console panel $name")
    val panel = newInstance[RacePanel](panelConfig.getString("class"),
      Array(classOf[RaceViewer], classOf[Option[Config]]),
      Array(this, Some(panelConfig))).get
    panel.styled("consolePanel")

    PanelEntry(name, panel, tooltip, expand)
  }

  def createClockPanel: Option[PanelEntry] = {
    config.getOptionalConfig("clock-panel") match {
      case Some(pconf) => if (pconf.isEmpty) None else Some(createPanelEntry(pconf))
      case None =>
        val clockPanel = (if (config.getBooleanOrElse("run-control", false)) {
          new ControlClockPanel(this)
        } else {
          new BasicClockPanel(this)
        }).styled("consolePanel")
        Some(PanelEntry("clock", clockPanel))
    }
  }

  def createViewPanel: Option[PanelEntry] = {
    config.getOptionalConfig("view-panel") match {
      case Some(pconf) => if (pconf.isEmpty) None else Some(createPanelEntry(pconf))
      case None => Some(PanelEntry("view", new ViewPanel(this).styled("consolePanel")))
    }
  }

  def createSyncPanel: Option[PanelEntry] = {
    config.getOptionalConfig("sync-panel") match {
      case Some(pconf) => if (pconf.isEmpty) None else Some(createPanelEntry(pconf))
      case None => Some(PanelEntry("sync", new SyncPanel(this).styled("consolePanel")))
    }
  }

  def createLayersPanel: Option[PanelEntry] = {
    config.getOptionalConfig("layers-panel") match {
      case Some(pconf) => if (pconf.isEmpty) None else Some(createPanelEntry(pconf))
      case None => Some(PanelEntry("layers", new LayerListPanel(this).styled("consolePanel")))
    }
  }


  def createDefaultPanels(collapsed: Set[String]): ListMap[String,PanelEntry] = {
    def panelEntry(name: String, c: RacePanel, tt: String=null) = name -> PanelEntry(name,c,tt,!collapsed.contains(name))
    def styled (c: RacePanel) = c.styled("consolePanel")

    var panels = new ListMap[String,PanelEntry]
    Seq(createClockPanel, createViewPanel, createSyncPanel, createLayersPanel).foreach { o=>
      ifSome(o) { e=>
        if (collapsed.contains(e.name)) e.expand = false
        panels = panels + (e.name -> e)
      }
    }

    panels = panels + (SelectedLayer -> PanelEntry(SelectedLayer,styled(emptyLayerInfoPanel)))
    panels = panels + (SelectedObject -> PanelEntry(SelectedObject,styled(emptyObjectPanel)))

    panels
  }

  def foreachPanel (f: (RacePanel)=>Unit): Unit = panels.foreach( e=> f(e._2.panel))

  def showConsolePanels(setVisible: Boolean) = frame.showConsolePanels(setVisible)
  def showConsolePanel(name: String, setVisible: Boolean) = frame.showConsolePanel(name, setVisible)

  // layer/object panel selection
  def getLayerPanel: Option[Component] = frame.getPanel(SelectedLayer)
  def setLayerPanel (c: Component) = if (panels.contains(SelectedLayer)) frame.setPanel(SelectedLayer, c)
  def dismissLayerPanel = if (panels.contains(SelectedLayer)) frame.setPanel(SelectedLayer, emptyLayerInfoPanel)

  def getObjectPanel: Option[Component] = frame.getPanel(SelectedObject)
  def setObjectPanel (c: Component) = if (panels.contains(SelectedObject)) frame.setPanel(SelectedObject, c)
  def dismissObjectPanel = if (panels.contains(SelectedObject)) frame.setPanel(SelectedObject, emptyObjectPanel)


  // we need this here because of universe specific loaders
  def newInstance[T: ClassTag] (clsName: String,
                                argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T] = {
    viewerActor.newInstance(clsName,argTypes,args)
  }

  def configurable[T: ClassTag](conf: Config): Option[T] = viewerActor.configurable(conf)

  /**
    * notify ViewListeners of a changed eyepoint/view direction
    * this is usually called by RaceViewInputHandler or RaceOrbitView
    * note that we don't query the current eyePosition here because the input handler might call this on
    * targeted views (we don't want to get notifications for temporary animation views)
    */
  def notifyViewChanged: Unit = {
    viewListeners.foreach(_.viewChanged(viewController.viewGoal))
  }

  //--- view (eye position) transitions

  // this does not animate and hence is the fast way to change the eye. Use for objects that have to stay at the
  // same screen coordinates, but the map is going to be updated discontinuously
  // NOTE - this short-circuits any ongoing animation

  def jumpToEyePosition(pos: Position): Unit = {
    viewController.moveEyePosition(pos)
  }

  def tgtEyePos = viewController.targetViewPos
  def tgtZoom = viewController.targetViewZoom

  //--- view animations
  def centerTo(pos: Position, transitionTime: Long=500) = {
    // FIXME - this uses the pos altitude, which should not be changed
    viewController.centerTo(pos,transitionTime)
  }
  def zoomTo (zoom: Double) = viewController.zoomTo(zoom)
  def panTo (pos: Position, zoom: Double) = viewController.panTo(pos,zoom)
  def pitchTo (endAngle: Angle) = viewController.pitchTo(endAngle)
  def rollTo (endAngle: Angle) = viewController.rollTo(endAngle)
  def headingTo (endAngle: Angle) = viewController.headingTo(endAngle)
  def headingPitchTo (endHeading: Angle, endPitch: Angle) = viewController.headingPitchTo(endHeading,endPitch)

  def zoomInOn (pos: Position) = {
    val zoom = pos.elevation + zoomDistance
    viewController.addPanToAnimator(pos, ZeroWWAngle, ZeroWWAngle,zoom, 1000, false)
  }


  /**
    * keep eye position but reset view angles and center position to earth center
    */
  def resetView = {
    val pos: Position = if (focusObject.isDefined) focusObject.get.pos else tgtEyePos
    viewController.addPanToAnimator(pos,ZeroWWAngle,ZeroWWAngle,tgtZoom,1000,true)
  }

  /**
    * global focus object management
    *
    * We only make sure there is at most a single focus object here. Any layer/object specific actions (including
    * rendering and view transition) have to be done by the initiating layer itself since we don't know anything
    * about the LayerObject here (other than it's layer and if it is focused)
    */
  def setFocused(obj: LayerObject, isFocused: Boolean): Unit = {
    if (isFocused) {
      ifSome(focusObject) { oldObj =>
        if (oldObj != obj) { // reset previous focus
          oldObj.layer.setFocused(oldObj,false, false) // no need to report back to us
        }
      }
      focusObject = Some(obj)
    } else {
      focusObject = None
    }
  }

  def resetFocused: Unit = {
    ifSome(focusObject) { o =>
      o.layer.setFocused(o, false,true)
      focusObject = None
    }
  }


  //--- layer change management
  def setLayerController (controller: LayerController) = layerController = Some(controller)
  def layerChanged (layer: Layer) = layerListeners.foreach(_.layerChanged(layer))
  def changeLayer (name: String, enable: Boolean) = layerController.foreach(_.changeLayer(name,enable))

  //--- object change management
  def objectChanged (obj: LayerObject, action: LayerObjectAction): Unit = {
    objectListener.foreach(_.objectChanged(obj,action))
  }
  def changeObject (id: String, layerName: String, action: LayerObjectAction): Option[RaceLayer] = {
    ifSome(getLayer(layerName)){ _.changeObject(id,action)}
  }

  def configuredLayerCategories(default: Set[String]): Set[String] = {
    if (config.hasPath("layer-categories")) config.getStringList("layer-categories").asScala.toSet else default
  }

  def redraw = redrawManager.redraw()
  def redrawNow = redrawManager.redrawNow()

  def getInViewChecker = InViewChecker(wwd)

  //--- race control ops
  def isPaused = viewerActor.isPaused

  def requestRacePause = viewerActor.requestPause
  def requestRaceResume = viewerActor.requestResume
  def requestRaceTermination = viewerActor.requestTermination

  def requestPauseResume = {
    if (isPaused) requestRaceResume else requestRacePause
  }

  // the callback notifications
  def onRaceStarted: Unit = foreachPanel(_.onRaceStarted)
  def onRacePaused: Unit = foreachPanel(_.onRacePaused)
  def onRaceResumed: Unit = foreachPanel(_.onRaceResumed)
  def onRaceTerminated: Unit = {
    if (displayable) {
      foreachPanel(_.onRaceTerminated)
      close
    }
  }
}