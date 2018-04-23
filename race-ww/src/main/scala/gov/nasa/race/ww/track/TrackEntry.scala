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

package gov.nasa.race.ww.track

import java.awt.image.BufferedImage
import java.awt.{Font, Point}

import gov.nasa.race._
import gov.nasa.race.track.{TrackedObject, Trajectory}
import gov.nasa.race.util.DateTimeUtils.hhmmss
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww._
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.render.{Material, Offset, PointPlacemark, PointPlacemarkAttributes}

/**
  * the link between rendering agnostic TrackObjects and (WW specific) rendering objects
  *
  * This aggregates all Renderables that can be associated with a given TrackObject based on viewer
  * state (eye position and selected options)
  */
class TrackEntry[T <: TrackedObject](var obj: T, var trajectory: Trajectory, val layer: TrackLayer[T]) extends LayerObject {

  //--- the renderables that can be associated with this entry
  protected var symbol: Option[TrackSymbol[T]] = Some(createSymbol)
  protected var path: Option[TrackPath[T]] = None
  protected var info: Option[InfoBalloon] = None
  protected var mark: Option[PointPlacemark] = None
  protected var model: Option[TrackModel[T]] = None

  var isCentered = false // do we center the view on the current placemark position
  var drawPathContour = false // do we draw a path contour for this track

  def viewPitch = layer.raceView.viewPitch
  def viewRoll = layer.raceView.viewRoll

  override def pos = obj

  //--- per-layer rendering resources
  def labelMaterial: Material = layer.labelMaterial
  def lineMaterial: Material = layer.lineMaterial
  def symbolImg: BufferedImage = layer.symbolImg
  def symbolImgScale: Double = 0.35
  def symbolHeading: Double = obj.heading.toDegrees
  def markImg: BufferedImage = layer.markImg
  def labelFont: Font = layer.labelFont
  def subLabelFont: Font = layer.subLabelFont

  //--- label and info text creation
  def labelText: String = if (obj.cs != obj.id) obj.cs else obj.id

  def infoText: String = {
    s"${obj.cs}\n${hhmmss.print(obj.date)}\n${obj.altitude.toFeet.toInt} ft\n${obj.heading.toDegrees.toInt}°\n${obj.speed.toKnots.toInt} kn"
  }

  //--- override creators in subclasses for more specialized types

  def createSymbol: TrackSymbol[T] = new TrackSymbol(this)

  def createPath: TrackPath[T] = new TrackPath(this)

  def createMark: PointPlacemark = {
    val m = new PointPlacemark(obj)
    m.setAltitudeMode(WorldWind.ABSOLUTE)
    val attrs = new PointPlacemarkAttributes
    attrs.setImage(markImg)
    attrs.setImageOffset(Offset.CENTER)
    m.setAttributes(attrs)
    m
  }

  def createInfo (screenPoint: Point): InfoBalloon = {
    val balloon = new InfoBalloon(infoText)
    balloon.setScreenPoint(screenPoint)
    balloon.setDrawOffset(new Point(-50, 10)) // FIXME should be computed
    balloon
  }


  override def id = obj.cs // required for pick support

  def hasAttrs = isCentered || path.isDefined || info.isDefined || mark.isDefined
  def hasModel = model.isDefined
  def hasAssignedModel = model.isDefined && model.get.isAssigned
  def hasSymbol = symbol.isDefined
  def hasPath = path.isDefined
  def hasInfo = info.isDefined
  def hasMark = mark.isDefined

  def setNewObj (newObj: T): Unit = {
    obj = newObj

    trajectory.add(newObj)
    ifSome(path) {_.addTrackPosition(newObj)}
  }

  def addRenderables = {
    ifSome(symbol) { layer.addRenderable }
    ifSome(model) { layer.addRenderable }
    ifSome(path) { layer.addRenderable }
  }

  def updateRenderables = {
    ifSome(symbol) { sym =>
      sym.update(obj)

      ifSome(info) { balloon =>
        balloon.setText(infoText)
        balloon.setScreenPoint(sym.getScreenPoint)
      }
      ifSome(mark) {
        _.setPosition(obj)
      }
    }

    ifSome(model) { _.update(obj) }

    if (isCentered) layer.centerEntry(this)
  }

  def removeRenderables = {
    ifSome(symbol) {layer.removeRenderable}; symbol = None
    ifSome(path) {layer.removeRenderable}; path = None
    ifSome(info) {layer.removeRenderable}; info = None
    ifSome(mark) {layer.removeRenderable}; mark = None
    ifSome(model) { layer.removeRenderable}; model = None
  }

  def setIconLevel: Unit = symbol.foreach { sym =>
    if (model.isDefined) sym.setLabelAttrs else sym.setIconAttrs
  }
  def setLabelLevel: Unit = symbol.foreach(_.setLabelAttrs)
  def setDotLevel: Unit = symbol.foreach(_.setDotAttrs)
  def setSymbolLevelAttrs = layer.symbolLevels.triggerInCurrentLevel(this)

  def setLinePosLevel: Unit = path.foreach(_.setLinePosAttrs)
  def setLineLevel: Unit = path.foreach(_.setLineAttrs)
  def setNoLineLevel: Unit = {} // FIXME - don't show but keep data
  def setPathLevelAttrs = layer.pathLevels.triggerInCurrentLevel(this)

  def setModel (newModel: Option[TrackModel[T]]) = {
    ifSome(newModel) { m =>
      ifSome(model) { layer.removeRenderable }
      m.assign(this)
      m.update(obj)
      layer.addRenderable(m)
      ifSome(symbol) { _.setLabelAttrs }  // no use to show the symbol image

    } orElse {
      ifSome(model){ m =>
        m.unAssign
        layer.removeRenderable(m)
        setSymbolLevelAttrs
      }
    }

    model = newModel
  }

  def show(showIt: Boolean) = {
    if (showIt && symbol.isEmpty) {
      symbol = Some(createSymbol)
      layer.addRenderable(symbol.get)

    } else if (!showIt && symbol.isDefined) {
      removeRenderables  // no symbol also means no other renderables
    }
  }

  def setPath(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && path.isEmpty) {
      path = Some(createPath)
      layer.addRenderable(path.get)

    } else if (!showIt && path.isDefined) {
      layer.removeRenderable(path.get)
      path = None
    }
  }

  def setPathContour (showIt: Boolean) = {
    if (showIt != drawPathContour) {
      drawPathContour = showIt
      ifSome(path) { p=> p.setContourAttrs(showIt) }
    }
  }

  def setMark(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && mark.isEmpty) {
      mark = Some(createMark)
      mark.foreach(layer.addRenderable)

    } else if (!showIt && mark.isDefined) {
      layer.removeRenderable(mark.get)
      mark = None
    }
  }

  def setInfo(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && info.isEmpty) {
      val balloon = createInfo(sym.getScreenPoint)
      info = Some(balloon)
      layer.addRenderable(balloon)

    } else if (!showIt && info.isDefined) {
      layer.removeRenderable(info.get)
      info = None
    }
  }

  def setCentered (centerIt: Boolean) = isCentered = centerIt

}