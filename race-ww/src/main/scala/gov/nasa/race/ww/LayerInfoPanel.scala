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
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing._
import gov.nasa.race.util.StringUtils._
import gov.nasa.race.ww.LayerInfoList._
import gov.nasa.worldwind.layers.Layer

import scala.collection.mutable.ListBuffer
import scala.swing._

trait LayerInfoPanel extends RacePanel {
  this: Component =>
  def layer: Layer

  // generics hooks that can be overridden to add actions that are executed
  // when the panel/layer is selected/unselected.
  // note this is not the same as Component visibility since panels can be shared
  // (in which case panels for unselected layers won't get invisible)
  def select: Unit = {}
  def unselect: Unit = {}
}

/**
 * a panel to display generic layer information
 */
abstract class BasicLayerInfoPanel extends BoxPanel(Orientation.Vertical) with LayerInfoPanel {

  class LayerInfoFields extends FieldPanel {
    val layerName = addField("name:")
    val description = addField("description:")
    val attributes = addField("attributes:")
    val activeAlt = addField("active alt:")
    //val effectiveAlt = addField("effective alt:")
    //val expiryTime = addField("expires:")
  }

  def setBasicLayerFields: Unit = {
    val layerInfo = layer.layerInfo
    val minActive = altToString( layer.getMinActiveAltitude)
    val maxActive = altToString( layer.getMaxActiveAltitude)
    val attrs = ListBuffer[String]()

    if (layer.isEnabled) attrs += "enabled"
    if (layer.isPickEnabled) attrs += "pick"
    if (layer.isNetworkRetrievalEnabled) attrs += "net"

    // those can cause NPEs - not yet sure if this is a WW bug
    //val minEffective = altToString( layer.getMinEffectiveAltitude(null))
    //val maxEffective = altToString( layer.getMaxEffectiveAltitude(null))

    fields.layerName.text = capLength(s"${layerInfo.name}")
    fields.description.text = capLength(s"${layerInfo.description}")
    fields.attributes.text = capLength(s"""${attrs.mkString(",")}""")
    fields.activeAlt.text = capLength(f"$minActive - $maxActive")

    //fields.effectiveAlt.text = f"$minEffective - $maxEffective"
    //fields.expiryTime.text   =   s"${MMddyyyyhhmmssZ.print(layer.getExpiryTime)}"
  }

  val fields: LayerInfoFields

  def altToString (d: Double) = {
    if (d < 0) "0"
    else if (d == Double.MaxValue) "∞"
    else f"${d}%,.0f"
  }
}

class SharedLayerInfoPanel extends BasicLayerInfoPanel {
  var layer: Layer = _

  // no additional fields
  val fields = new LayerInfoFields {
    setContents
  }.styled()

  contents += fields

  def setLayer (newLayer: Layer): Unit = {
    layer = newLayer
    setBasicLayerFields
  }
}

/**
  * a static panel that knows the number of items at construction time
  *
  * note that we require the size as ctor argument so that respective layers do not
  * cause runtime exceptions by creating the layer before creating their data
  */
class StaticLayerInfoPanel (val layer: RaceLayer, val nItems: Int) extends BasicLayerInfoPanel {
  class StaticLayerInfoFields extends LayerInfoFields {
    val itemsLabel  = addField("num items:", "…")
    setContents
  }

  val fields = new StaticLayerInfoFields().styled()

  contents += fields

  setBasicLayerFields
  fields.itemsLabel.text = s"$nItems"
}

import scala.concurrent.duration._

class DynamicLayerInfoPanel (val layer: SubscribingRaceLayer) extends BasicLayerInfoPanel
                                                              with AncestorObservable {
  class DynamicLayerInfoFields extends LayerInfoFields {
    val itemsLabel  = addField("num items:", "…")
    val updateLabel = addField("msg/sec:", "…")
    setContents
  }

  val fields = new DynamicLayerInfoFields().styled()

  contents += fields
  setBasicLayerFields

  val timer = new SwingTimer(3.seconds)
  var startTime: Long = System.currentTimeMillis
  var startCount: Int = layer.updateCount

  timer.whenExpired(processTimerEvent)
  timer.start

  def processTimerEvent = {
    // we don't turn off timer events since some layers might use them to update renderables
    // (e.g. for showing flight paths).
    // No use to update the fields though if we are not showing
    if (showing) {
      val rate = (layer.updateCount-startCount).toDouble / ((System.currentTimeMillis-startTime)/1000)
      fields.itemsLabel.text  = s"${layer.size}"
      fields.updateLabel.text = f"$rate%.1f"
    }
  }

  def wwd = layer.wwd
}

