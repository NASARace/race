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

import gov.nasa.race.geo.GeoPositioned
import gov.nasa.race.swing.{SelectionPreserving, VisibilityBounding}

import scala.collection.mutable.{HashMap => MHashMap}
import scala.swing.{Component, ListView}


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
  def setAttr(attr: LayerObjectAttr, cond: Boolean)
  def isAttrSet(attr: LayerObjectAttr): Boolean

  // global RaceViewer focus (automatic follow-object)
  def setFocused(cond: Boolean): Unit
  def isFocused: Boolean

  def setVisible(cond: Boolean): Unit
  def isVisible: Boolean
}

/**
  * marker trait for LayerObject display attributes
  * note this is not sealed so that RaceLayers can add their own
  */
trait LayerObjectAttr

object LayerObjectAction {
  protected val map = new MHashMap[String,LayerObjectAction]
  def get (name: String): Option[LayerObjectAction] = map.get(name)
}

/**
  * marker trait for actions that can be performed on LayerObjects
  */
trait LayerObjectAction {
  val name: String = {
    val s = getClass.getSimpleName
    if (s.endsWith("$")) s"LayerObjectAction.${s.substring(0,s.length-1)}"
    else s"LayerObjectAction.$s"
  }
  LayerObjectAction.map += name -> this
}


object LayerObject {

  //--- the predefined LayerObject display attributes
  object PathAttr extends LayerObjectAttr
  object InfoAttr extends LayerObjectAttr
  object MarkAttr extends LayerObjectAttr

  //--- predefined LayerObject actions
  object Select extends LayerObjectAction
  object ShowPanel extends LayerObjectAction
  object DismissPanel extends LayerObjectAction
  object StartFocus extends LayerObjectAction
  object StopFocus extends LayerObjectAction
  object ShowPath extends LayerObjectAction
  object HidePath extends LayerObjectAction
  object ShowContour extends LayerObjectAction
  object HideContour extends LayerObjectAction
  object ShowInfo extends LayerObjectAction
  object HideInfo extends LayerObjectAction
  object ShowMark extends LayerObjectAction
  object HideMark extends LayerObjectAction
}

trait LayerObjectListener {
  def objectChanged(obj: LayerObject, action: LayerObjectAction)
}
