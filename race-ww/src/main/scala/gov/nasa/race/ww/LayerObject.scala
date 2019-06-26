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

import gov.nasa.race._
import gov.nasa.race.geo.GeoPositioned
import gov.nasa.race.ww.LayerObjectAction.LayerObjectAction
import gov.nasa.race.ww.LayerObjectAttribute.LayerObjectAttribute


/**
  * minimal interface of objects managed by RaceLayers
  *
  * note that LayerObjects don't have to be dynamic, but they need to be associated with
  * a GeoPosition, they need a layer-unique id, and they need to know their owning layer
  */
trait LayerObject {
  def id: String
  def layer: RaceLayer
  def pos: GeoPositioned

  // per object display attribute management
  def setAttr(attr: LayerObjectAttribute, cond: Boolean): Unit
  def isAttrSet(attr: LayerObjectAttribute): Boolean

  // global RaceViewer focus (automatic follow-object)
  def setFocused(cond: Boolean): Unit
  def isFocused: Boolean

  def setVisible(cond: Boolean): Unit
  def isVisible: Boolean
}

/**
  * predefined actions for LayerObjects
  */
object LayerObjectAction extends Enumeration {
  type LayerObjectAction = Value

  val Select       = Value
  val ShowPanel    = Value
  val DismissPanel = Value
  val StartFocus   = Value
  val StopFocus    = Value
  val ShowPath     = Value
  val HidePath     = Value
  val ShowContour  = Value
  val HideContour  = Value
  val ShowInfo     = Value
  val HideInfo     = Value
  val ShowMark     = Value
  val HideMark     = Value

  def get(name: String): Option[Value] = trySome(LayerObjectAction.withName(name))
}


/**
  * predefined display attributes for LayerObjects
  */
object LayerObjectAttribute extends Enumeration {
  type LayerObjectAttribute = Value

  val Path = Value
  val Contour = Value
  val Info = Value
  val Mark = Value

  def get(name: String): Option[Value] = trySome(LayerObjectAttribute.withName(name))

  def getAction (attr: Value, cond: Boolean): Option[LayerObjectAction] = {
    attr match {
      case Path => Some( if (cond) LayerObjectAction.ShowPath else LayerObjectAction.HidePath)
      case Contour => Some( if (cond) LayerObjectAction.ShowContour else LayerObjectAction.HideContour)
      case Info => Some( if (cond) LayerObjectAction.ShowInfo else LayerObjectAction.HideInfo)
      case Mark => Some( if (cond) LayerObjectAction.ShowMark else LayerObjectAction.HideMark)
      case _ => None
    }
  }
}

trait LayerObjectListener {
  def objectChanged(obj: LayerObject, action: LayerObjectAction): Unit
}
