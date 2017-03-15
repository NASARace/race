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

import java.awt.Point

import gov.nasa.race._
import gov.nasa.race.air.{AbstractFlightPath, InFlightAircraft}
import gov.nasa.race.ww.{InfoBalloon, _}
import gov.nasa.worldwind.WorldWind
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.render.{Offset, PointPlacemark, PointPlacemarkAttributes}

/**
  * class that aggregates all Renderables that can be associated with a given InFlightAircraft
  */
class FlightEntry[T <: InFlightAircraft](var obj: T, var flightPath: AbstractFlightPath, val layer: FlightLayer[T]) extends LayerObject {

  override def id = obj.cs

  //--- the renderables that can be associated with this entry
  protected var symbol: Option[FlightSymbol[T]] = layer.getSymbol(this)
  protected var path: Option[FlightPath[T]] = None
  protected var info: Option[InfoBalloon] = None
  protected var mark: Option[PointPlacemark] = None
  protected var model: Option[FlightModel[T]] = None

  protected var followPosition = false // do we center the view on the current placemark position

  def hasAttrs = followPosition || path.isDefined || info.isDefined || mark.isDefined
  def hasModel = model.isDefined
  def hasSymbol = symbol.isDefined
  def hasPath = path.isDefined
  def hasInfo = info.isDefined
  def hasMark = mark.isDefined
  def isCentered = followPosition

  def setNewObj (newObj: T) = {
    obj = newObj

    flightPath.add(newObj)
    ifSome(path) {_.addFlightPosition(newObj)}
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
        balloon.setText(sym.getStringValue(AVKey.DISPLAY_NAME))
        balloon.setScreenPoint(sym.getScreenPoint)
      }
      ifSome(mark) {
        _.setPosition(obj)
      }

      if (followPosition) layer.centerEntry(this)
    }

    ifSome(model) { _.update(obj) }
  }

  def removeRenderables = {
    ifSome(symbol) {layer.removeRenderable}; symbol = None
    ifSome(path) {layer.removeRenderable}; path = None
    ifSome(info) {layer.removeRenderable}; info = None
    ifSome(mark) {layer.removeRenderable}; mark = None
    ifSome(model) { layer.removeRenderable}; model = None
  }


  def setDotLevel = symbol.foreach{_.setDotAttrs}
  def setLabelLevel = symbol.foreach{_.setLabelAttrs}
  def setSymbolLevel = symbol.foreach{_.setSymbolAttrs}

  def setLineLevel = path.foreach{_.setLineAttrs}
  def setLinePosLevel = path.foreach{_.setLinePosAttrs}

  def setModel (newModel: Option[FlightModel[T]]) = {
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
        ifSome(symbol) { sym => layer.setFlightLevel(this) }
      }
    }
    model = newModel
  }

  def show(showIt: Boolean) = {
    if (showIt && symbol.isEmpty) {
      symbol = layer.getSymbol(this)
      layer.addRenderable(symbol.get)

    } else if (!showIt && symbol.isDefined) {
      removeRenderables  // no symbol also means no other renderables
    }
  }

  def setPath(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && path.isEmpty) {
      path = Some(new FlightPath(this))
      layer.addRenderable(path.get)

    } else if (!showIt && path.isDefined) {
      layer.removeRenderable(path.get)
      path = None
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

  def createMark: PointPlacemark = withDo(new PointPlacemark(obj)) { m =>
    m.setAltitudeMode(WorldWind.ABSOLUTE)
    val attrs = new PointPlacemarkAttributes
    attrs.setImage(layer.markImage(obj))
    attrs.setImageOffset(Offset.CENTER)
    m.setAttributes(attrs)
  }

  def setInfo(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && info.isEmpty) {
      sym.setDisplayName(true)
      val balloon = new InfoBalloon(sym.getStringValue(AVKey.DISPLAY_NAME))
      balloon.setScreenPoint(sym.getScreenPoint)
      balloon.setDrawOffset(new Point(-50, 10)) // FIXME should be computed
      info = Some(balloon)
      layer.addRenderable(balloon)

    } else if (!showIt && info.isDefined) {
      sym.setDisplayName(false)
      layer.removeRenderable(info.get)
      info = None
    }
  }

  def followPosition(followIt: Boolean): Unit = {
    followPosition = followIt
  }


  def updateRenderingAttributes = {
    ifSome(symbol){_.updateAttributes}
  }
}