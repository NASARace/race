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

import java.awt.Color

import com.github.nscala_time.time.Imports._
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.{CompactFlightPath, FlightInfo, FlightInfoUpdateRequest, InFlightAircraft}
import gov.nasa.race.common.Threshold
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages._
import gov.nasa.race.uom.Length._
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww._
import gov.nasa.race.ww.air.FlightRenderLevel.FlightRenderLevel
import gov.nasa.race.ww.air.PathRenderLevel.PathRenderLevel

import scala.collection.mutable.{Map => MutableMap}


/**
  * abstract layer class to display aircraft in flight
  */
abstract class FlightLayer[T <:InFlightAircraft](val raceView: RaceView, config: Config)
                                  extends SubscribingRaceLayer(raceView,config)
                                     with DynamicRaceLayerInfo
                                     with AltitudeSensitiveLayerInfo {

  val panel = createLayerInfoPanel
  val entryPanel = createEntryPanel

  val flightInfoBase = config.getOptionalString("flightinfo-base")

  //--- AircraftPlacemark attributes
  def defaultSymbolColor = Color.yellow
  val color = config.getColorOrElse("color", defaultSymbolColor)

  def defaultPlaneImg = Images.getPlaneImage(color)
  val planeImg = defaultPlaneImg

  val markImg = Images.defaultMarkImg
  val labelColor = toABGRString(color)
  val lineColor = labelColor
  var labelThreshold = config.getDoubleOrElse("label-altitude", Meters(1400000.0).toMeters)
  var symbolThreshold = config.getDoubleOrElse("symbol-altitude", Meters(1000000.0).toMeters)
  var flightDetails: FlightRenderLevel = getFlightRenderLevel(eyeAltitude)

  def image (t: T) = planeImg
  def markImage (t: T) = markImg

  //--- AircraftPath attributes
  val pathColor = color
  val showPositions = config.getBooleanOrElse("show-positions", true)
  val linePosThreshold = config.getDoubleOrElse("position-altitude", Meters(30000.0).toMeters)
  var pathDetails: PathRenderLevel = getPathRenderLevel(eyeAltitude)

  thresholds ++= Seq(
    new Threshold(linePosThreshold, setLinePosLevel, setLineLevel),
    new Threshold(symbolThreshold, setSymbolLevel, setLabelLevel),
    new Threshold(labelThreshold,  setLabelLevel, setDotLevel)
  )

  //--- the data we manage
  val flights = MutableMap[String,FlightEntry[T]]()

  val noDisplayFilter: (FlightEntry[T])=>Boolean = (f) => true
  var displayFilter: (FlightEntry[T])=>Boolean = noDisplayFilter

  override def size = flights.size

  //--- end ctor

  // override for specialized LayerInfoPanel
  def createLayerInfoPanel = new FlightLayerInfoPanel(raceView,this).styled('consolePanel)
  def createEntryPanel = new FlightEntryPanel(raceView,this).styled('consolePanel)

  def getFlight (cs: String) = flights.get(cs).map( _.obj )

  def matchingFlights (f: FlightEntry[T]=>Boolean): Seq[FlightEntry[T]] = {
    flights.foldLeft(Seq.empty[FlightEntry[T]])( (acc,e) => {
      val flight = e._2
      if (f(flight)) flight +: acc else acc
    })
  }

  def foreachFlight (f: FlightEntry[T]=>Unit): Unit = flights.foreach( e=> f(e._2))

  //--- rendering detail level management
  def getFlightRenderLevel (alt: Double) = {
    if (alt > labelThreshold) FlightRenderLevel.Dot
    else if (alt > symbolThreshold) FlightRenderLevel.Label
    else FlightRenderLevel.Symbol
  }
  def setFlightRenderLevel (level: FlightRenderLevel,f: (FlightEntry[T])=>Unit): Unit = {
    flightDetails = level
    flights.foreach(e=> f(e._2))
    redrawNow
  }

  def setDotLevel    = setFlightRenderLevel( FlightRenderLevel.Dot, (e)=> e.setDotLevel)
  def setLabelLevel  = setFlightRenderLevel( FlightRenderLevel.Label, (e)=> e.setLabelLevel)
  def setSymbolLevel = setFlightRenderLevel( FlightRenderLevel.Symbol, (e)=> e.setSymbolLevel)

  def setFlightLevel (e: FlightEntry[T]) = {
    flightDetails match {
      case FlightRenderLevel.Dot => e.setDotLevel
      case FlightRenderLevel.Label => e.setLabelLevel
      case FlightRenderLevel.Symbol => e.setSymbolLevel
    }
  }

  def getPathRenderLevel (alt: Double) = if (alt > linePosThreshold) PathRenderLevel.Line else PathRenderLevel.LinePos
  def setPathRenderLevel (level: PathRenderLevel,f: (FlightEntry[T])=>Unit): Unit = {
    pathDetails = level
    flights.foreach(e=> f(e._2))
    redrawNow
  }
  def setLineLevel   = setPathRenderLevel( PathRenderLevel.Line, (e)=> e.setLineLevel)
  def setLinePosLevel   = setPathRenderLevel( PathRenderLevel.LinePos, (e)=> e.setLinePosLevel)

  def showPathPositions = showPositions && eyeAltitude < linePosThreshold

  def createFlightPath (fpos: T) = new CompactFlightPath

  def getSymbol (e: FlightEntry[T]): Option[FlightSymbol[T]] = {
    Some(new FlightSymbol(e))
  }

  def dismissEntryPanel (e: FlightEntry[T]) = {
    if (entryPanel.isShowing(e)) {
      raceView.dismissObjectPanel
      raceView.objectChanged(e, DismissPanel)
    }
  }

  def setDisplayFilter(filter: (FlightEntry[T])=>Boolean) = {
    displayFilter = filter
    if (filter eq noDisplayFilter) {
      flights.foreach(_._2.show(true))
    } else {
      flights.foreach { e => e._2.show(filter(e._2)) }
    }
    redraw
  }

  def changeObjectAttr(e: FlightEntry[T], f: =>Unit, action: String) = {
    f
    changedFlightEntryOptions(e,action)
    redraw
  }

  //--- layer object sync actions
  final val StartCenter = "StartCenter"
  final val StopCenter = "StopCenter"
  final val ShowPath = "ShowPath"
  final val HidePath = "HidePath"
  final val ShowInfo = "ShowInfo"
  final val HideInfo = "HideInfo"
  final val ShowMark = "ShowMark"
  final val HideMark = "HideMark"

  override def changeObject(objectId: String, action: String) = {
    ifSome(flights.get(objectId)) { e =>
      action match {
          //--- generic ones
        case `Select`       => selectFlightEntry(e)
        case `ShowPanel`    => setFlightEntryPanel(e)
        case `DismissPanel` => dismissEntryPanel(e)
          //--- our own ones
        case `StartCenter` => startCenteringFlightEntry(e)
        case `StopCenter` => ifSome(centeredEntry) { ce=> if (ce eq e) stopCenteringFlightEntry }
        case `ShowPath`   => changeObjectAttr(e, e.setPath(true), action)
        case `HidePath`   => changeObjectAttr(e, e.setPath(false), action)
        case `ShowInfo`   => changeObjectAttr(e, e.setInfo(true), action)
        case `HideInfo`   => changeObjectAttr(e, e.setInfo(false), action)
        case `ShowMark`   => changeObjectAttr(e, e.setMark(true), action)
        case `HideMark`   => changeObjectAttr(e, e.setMark(false), action)
        case other => // ignore
      }
    }
  }

  /**
    * the pick handler interface
    * Note we already know this object belongs to this layer
    */
  override def selectObject(o: RaceLayerPickable, a: EventAction) = {
    o.layerItem match {
      case e: FlightEntry[T] =>
        a match {
          case EventAction.LeftClick => selectFlightEntry(e)
          case EventAction.LeftDoubleClick => setFlightEntryPanel(e)
          case other => // ignored
        }
      case other => // ignored
    }
  }

  def selectFlightEntry (e: FlightEntry[T]) = {
    panel.trySelectFlightEntry(e)
    raceView.objectChanged(e,Select)
  }

  def setFlightEntryPanel(e: FlightEntry[T]) = {
    if (!entryPanel.isShowing(e)) {
      entryPanel.setFlightEntry(e)
      raceView.setObjectPanel(entryPanel)
      raceView.objectChanged(e,ShowPanel)
    }
  }

  //--- create, update and remove FlightEntries

  // here so that it can be overridden by subclasses, which can by useful in case the layer has to manage
  // resources that are display relevant for FlightEntries (such as 3D models)
  protected def createFlightEntry(fpos: T): FlightEntry[T] = new FlightEntry[T](fpos,createFlightPath(fpos), this)

  def addFlightEntryAttributes(e: FlightEntry[T]): Unit = e.addRenderables
  def updateFlightEntryAttributes (e: FlightEntry[T]): Unit = e.updateRenderables
  def releaseFlightEntryAttributes(e: FlightEntry[T]): Unit = e.removeRenderables

  protected def addFlightEntry (fpos: T) = {
    val e = createFlightEntry(fpos)
    flights += (fpos.cs -> e)

    if (displayFilter(e)) {
      addFlightEntryAttributes(e)
      wwdRedrawManager.redraw()
    }
    // we don't add to the panel here since it might have an active query and the new entry might not match
  }

  protected def updateFlightEntry (e: FlightEntry[T], fpos: T) = {
    if (e.obj.date < fpos.date) { // don't overwrite new with old data
      e.setNewObj(fpos)
      updateFlightEntryAttributes(e)
      if (e.hasSymbol) wwdRedrawManager.redraw()
      if (entryPanel.isShowing(e)) entryPanel.update
    }
  }

  protected def removeFlightEntry (e: FlightEntry[T]) = {
    val wasShowing = e.hasSymbol
    releaseFlightEntryAttributes(e)
    flights -= e.obj.cs
    if (wasShowing) wwdRedrawManager.redraw()
    panel.removedEntry(e)

    if (entryPanel.isShowing(e)) entryPanel.update
  }

  //--- flight entry centering

  var centeredEntry: Option[FlightEntry[T]] = None

  def centerEntry (e: FlightEntry[T]) = {
    if (e.hasAssignedModel) {
      raceView.centerOn(e.obj)
    } else {
      raceView.panToCenter(e.obj)
    }
  }

  def startCenteringFlightEntry(e: FlightEntry[T]) = {
    ifSome(centeredEntry){_.followPosition(false)}
    e.followPosition(true)
    raceView.panToCenter(e.obj)
    centeredEntry = Some(e)
    changedFlightEntryOptions(e,StartCenter)
  }
  def stopCenteringFlightEntry = {
    ifSome(centeredEntry) { e =>
      e.followPosition(false)
      centeredEntry = None
      changedFlightEntryOptions(e,StopCenter)
    }
  }

  def changedFlightEntryOptions (e: FlightEntry[T], action: String) = {
    panel.changedFlightEntryOptions
    entryPanel.changedFlightEntryOptions
    raceView.objectChanged(e,action)
  }

  def requestFlightInfoUpdates (e: FlightEntry[T]) = {
    ifSome(flightInfoBase) { baseChannel =>
      val cs = e.obj.cs
      val channel = s"$baseChannel/$cs"
      actor.subscribe(channel)
      request(channel, Some(FlightInfoUpdateRequest(cs)))
    }
  }
  def releaseFlightInfoUpdates (e: FlightEntry[T]) = {
    ifSome(flightInfoBase) { baseChannel =>
      val cs = e.obj.cs
      val channel = s"$baseChannel/$cs"
      actor.unsubscribe(channel)
      release(channel, Some(FlightInfoUpdateRequest(cs)))
    }
  }

  override def handleMessage = {
    case BusEvent(_,fInfo:FlightInfo,_) => entryPanel.setFlightInfo(fInfo)
    case DelayedAction(_,action) => action()
  }
}


