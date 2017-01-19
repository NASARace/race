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

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.{CompactFlightPath, FlightInfo, FlightInfoUpdateRequest, InFlightAircraft}
import gov.nasa.race.common.Threshold
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.Messages.DelayedAction
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww.EventAction.EventAction
import gov.nasa.race.ww._
import gov.nasa.race.ww.air.FlightRenderLevel.FlightRenderLevel
import gov.nasa.race.ww.air.PathRenderLevel.PathRenderLevel
import gov.nasa.worldwind.geom.Position

import scala.collection.mutable.{Map => MutableMap}

/**
  * abstract layer class to display aircraft in flight
  */
abstract class FlightLayer[T <:InFlightAircraft](val raceView: RaceView, config: Config)
                                  extends SubscribingRaceLayer(raceView,config)
                                     with DynamicRaceLayerInfo
                                     with AltitudeSensitiveLayerInfo
                                     with FlightModelClient[T] {

  val panel = new FlightLayerInfoPanel(raceView,this).styled('consolePanel)
  val entryPanel = new FlightEntryPanel(raceView,this).styled('consolePanel)

  val flightInfoBase = config.getOptionalString("flightinfo-base")

  //--- AircraftPlacemark attributes
  def defaultSymbolColor = Color.yellow
  val color = config.getColorOrElse("color", defaultSymbolColor)
  val planeImg = Images.getPlaneImage(color)
  val markImg = Images.defaultMarkImg
  val labelColor = toABGRString(color)
  val lineColor = labelColor
  var labelThreshold = config.getDoubleOrElse("label-altitude", 1400000.0)
  var symbolThreshold = config.getDoubleOrElse("symbol-altitude", 1000000.0)
  var flightDetails: FlightRenderLevel = getFlightRenderLevel(eyeAltitude)

  def image (t: T) = planeImg
  def markImage (t: T) = markImg

  //--- AircraftPath attributes
  val pathColor = color
  val showPositions = config.getBooleanOrElse("show-positions", true)
  val linePosThreshold = config.getDoubleOrElse("position-altitude", 40000.0)
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

  def getSymbol (e: FlightEntry[T]): Option[AircraftPlacemark[T]] = {
    Some(new AircraftPlacemark(e))
  }

  def centerOn(pos: Position) = raceView.centerOn(pos)

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
        case `StartCenter` => centerFlightEntry(e)
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

  def addFlightEntry (fpos: T) = {
    val e = new FlightEntry[T](fpos,createFlightPath(fpos), this)
    flights += (fpos.cs -> e)

    if (displayFilter(e)) {
      e.addRenderables
      wwdRedrawManager.redraw()
    }
    // we don't add to the panel here since it might have an active query and the new entry might not match
  }

  def updateFlightEntry (e: FlightEntry[T], fpos: T) = {
    e.updateAircraft(fpos)
    if (e.hasSymbol) wwdRedrawManager.redraw()
    if (entryPanel.isShowing(e)) entryPanel.update
  }

  def removeFlightEntry (e: FlightEntry[T]) = {
    val wasShowing = e.hasSymbol
    e.removeRenderables
    flights -= e.obj.cs
    if (wasShowing) wwdRedrawManager.redraw()
    panel.removedEntry(e)

    if (entryPanel.isShowing(e)) entryPanel.update
  }

  //--- flight entry centering
  var centeredEntry: Option[FlightEntry[T]] = None
  def centerFlightEntry (e: FlightEntry[T]) = {
    ifSome(centeredEntry){_.followPosition(false)}
    e.followPosition(true)
    centerOn(e.obj)
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
    case other => warning(f"$name ignoring message $other%30.30s..")
  }
}


