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
import gov.nasa.race.air.{FlightPath, InFlightAircraft}
import gov.nasa.race.ww.{InfoBalloon, _}
import gov.nasa.worldwind.avlist.AVKey
import gov.nasa.worldwind.render.ScreenImage
import com.github.nscala_time.time.Imports._

/**
  * class that aggregates all Renderables that can be associated with a given InFlightAircraft
  */
class FlightEntry[T <: InFlightAircraft](var obj: T, var flightPath: FlightPath, val layer: FlightLayer[T]) extends LayerObject {

  override def id = obj.cs

  //--- the renderables that can be associated with this entry
  protected var symbol: Option[AircraftPlacemark[T]] = Some(new AircraftPlacemark(this))
  protected var path: Option[AircraftPath[T]] = None
  protected var info: Option[InfoBalloon] = None
  protected var mark: Option[ScreenImage] = None

  protected var followPosition = false // do we center the view on the current placemark position

  def hasAttrs = followPosition || path.isDefined || info.isDefined || mark.isDefined
  def hasSymbol = symbol.isDefined
  def hasPath = path.isDefined
  def hasInfo = info.isDefined
  def hasMark = mark.isDefined
  def isCentered = followPosition

  def addRenderables = {
    ifSome(symbol) {layer.addRenderable(_)}
    ifSome(path) {layer.addRenderable(_)}
  }

  def removeRenderables = {
    ifSome(symbol) {layer.removeRenderable}; symbol = None
    ifSome(path) {layer.removeRenderable}; path = None
    ifSome(info) {layer.removeRenderable}; info = None
    ifSome(mark) {layer.removeRenderable}; mark = None
  }

  def setDotLevel = symbol.foreach{_.setDotAttrs}
  def setLabelLevel = symbol.foreach{_.setLabelAttrs}
  def setSymbolLevel = symbol.foreach{_.setSymbolAttrs}

  def setLineLevel = path.foreach{_.setLineAttrs}
  def setLinePosLevel = path.foreach{_.setLinePosAttrs}

  def show(showIt: Boolean) = {
    if (showIt && symbol.isEmpty) {
      symbol = Some(new AircraftPlacemark(this))
      layer.addRenderable(symbol.get)

    } else if (!showIt && symbol.isDefined) {
      removeRenderables  // no symbol also means no other renderables
    }
  }

  def setPath(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && path.isEmpty) {
      path = Some(new AircraftPath(this))
      layer.addRenderable(path.get)

    } else if (!showIt && path.isDefined) {
      layer.removeRenderable(path.get)
      path = None
    }
  }

  def setMark(showIt: Boolean) = ifSome(symbol) { sym =>
    if (showIt && mark.isEmpty) {
      val img = new ScreenImage
      img.setImageSource(layer.markImage(obj))
      img.setScreenOffset(sym.getScreenOffset)
      mark = Some(img)
      layer.addRenderable(img)

    } else if (!showIt && mark.isDefined) {
      layer.removeRenderable(mark.get)
      mark = None
    }
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

  def updateAircraft(newObj: T) = {
    if (newObj.date > obj.date) { // only update if more recent than what we already have
      obj = newObj
      flightPath.add(newObj)

      ifSome(symbol) { sym =>
        sym.update(newObj)

        ifSome(path) {
          _.addFlightPosition(newObj)
        }
        ifSome(info) { balloon =>
          balloon.setText(sym.getStringValue(AVKey.DISPLAY_NAME))
          balloon.setScreenPoint(sym.getScreenPoint)
        }
        ifSome(mark) {
          _.setScreenOffset(sym.getScreenOffset)
        }

        if (followPosition) layer.centerOn(obj)
      }
    }
  }

  def updateRenderingAttributes = {
    ifSome(symbol){_.updateAttributes}
  }
}