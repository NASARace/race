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

import gov.nasa.race.common.StringUtils._
import gov.nasa.race.common._
import gov.nasa.race.swing._
import gov.nasa.race.swing.Style._
import gov.nasa.race.ww.LayerInfoList._
import gov.nasa.worldwind.layers.Layer

import scala.collection.mutable.ListBuffer
import scala.swing._

trait LayerInfoPanel extends Container { // bad - this has to be a trait, but therefore its not a Component
  def setLayer (li: Layer): Unit
}

/**
 * a panel to display generic layer information
 */
abstract class GenericLayerInfoPanel extends BoxPanel(Orientation.Vertical) with LayerInfoPanel {

  class LayerInfoFields extends FieldPanel {
    val layerName = addField("name:")
    val description = addField("description:")
    val attributes = addField("attributes:")
    val activeAlt = addField("active alt:")
    //val effectiveAlt = addField("effective alt:")
    //val expiryTime = addField("expires:")
  }

  val fields: LayerInfoFields

  def altToString (d: Double) = {
    if (d < 0) "0"
    else if (d == Double.MaxValue) "∞"
    else f"${d}%,.0f"
  }

  // this can be used to set the dynamic  info in case this panel is used
  // for a number of layers
  override def setLayer (layer: Layer) = {
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
}

class DefaultLayerInfoPanel extends GenericLayerInfoPanel {
  // no additional fields
  val fields = new LayerInfoFields {
    setContents
  }.styled()

  contents += fields
}

import scala.concurrent.duration._

class DynamicLayerInfoPanel extends GenericLayerInfoPanel {

  var layer: DynamicRaceLayerInfo = _  // panel instance can be re-used for different layers

  val fields = new LayerInfoFields {
    val itemsLabel  = addField("num items:", "…")
    val updateLabel = addField("msg/sec:", "…")
    setContents
  }.styled()

  val timer = new SwingTimer(3.seconds)
  var startTime: Long = 0
  var startCount: Int = 0

  contents += fields

  override def setLayer (l: Layer): Unit = {
    super.setLayer(l)
    l match {
      case l: DynamicRaceLayerInfo =>
        layer = l
        timer.start
        startTime = System.currentTimeMillis
        startCount = l.count
      case _ =>
    }
  }

  timer.whenExpired(processTimerEvent)
  def processTimerEvent = {
    if (!showing) {
      timer.stop
    } else {
      ifNotNull(layer) { l =>
        val rate = (l.count-startCount).toDouble / ((System.currentTimeMillis-startTime)/1000)
        fields.itemsLabel.text  = itemsText(l)
        fields.updateLabel.text = updateText(rate)
      }
    }
  }

  def itemsText (l: DynamicRaceLayerInfo) = s"${l.size}"
  def updateText (rate: Double) = f"$rate%.1f"

  def wwd = layer.wwd
}
