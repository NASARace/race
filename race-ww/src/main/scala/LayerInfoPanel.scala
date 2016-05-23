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

import gov.nasa.race.common._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
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
abstract class GenericLayerInfoPanel  extends BoxPanel(Orientation.Vertical) with LayerInfoPanel {
  final val maxValueLength=26

  val nameLabel = new Label().styled('fieldValue)
  val descrLabel = new Label().styled('fieldValue)
  val attrLabel = new Label().styled('fieldValue)
  val activeAlt = new Label().styled('fieldValue)
  //val effectiveAlt = new Label("effective alt:").styled('fieldValue)
  //val expiryTime = new Label("expires:").styled('fieldValue)

  val lines = ListBuffer[(Label,Label)]( // can be extended by subclasses
    (new Label("name:").styled('fieldName), nameLabel),
    (new Label("description:").styled('fieldName), descrLabel),
    (new Label("attributes:").styled('fieldName), attrLabel),
    (new Label("active alt:").styled('fieldName), activeAlt)
  )

  def setContents = {
    val labelPanel = new GBPanel {
      val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West, insets = (1, 3, 1, 3), weighty = 0.1)
      for ((linePair, i) <- lines.zipWithIndex) {
        layout(linePair._1) = c(0, i).weightx(0)
        layout(linePair._2) = c(1, i).weightx(1)
      }
    } styled ('fieldGrid)

    val scrollPane = new ScrollPane(labelPanel).styled()
    contents += scrollPane
  }

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

    nameLabel.text = stringCap(s"${layerInfo.name}")
    descrLabel.text = stringCap(s"${layerInfo.description}")
    attrLabel.text = stringCap(s"""${attrs.mkString(",")}""")
    activeAlt.text = stringCap(f"$minActive - $maxActive")
    //effectiveAlt.text = f"$minEffective - $maxEffective"
    //expiryTime.text   =   s"${MMddyyyyhhmmssZ.print(layer.getExpiryTime)}"
  }

  def stringCap (s: String): String = {
    if (s.length < maxValueLength) s
    else s.substring(0, maxValueLength-3) + "src/main"
  }
}

class DefaultLayerInfoPanel extends GenericLayerInfoPanel {
  // no additional fields
  setContents
}

import scala.concurrent.duration._

class DynamicLayerInfoPanel extends GenericLayerInfoPanel {
  val timer = new SwingTimer(3.seconds)
  val itemsLabel = new Label().styled('fieldValue)
  val updateLabel = new Label().styled('fieldValue)
  var layer: DynamicRaceLayerInfo = _
  var startTime: Long = 0
  var startCount: Int = 0

  timer.whenExpired {
    if (!showing) {
      timer.stop
    } else {
      ifNotNull(layer) { l =>
        val rate = (l.count-startCount).toDouble / ((System.currentTimeMillis-startTime)/1000)
        itemsLabel.text = stringCap(s"${l.size}")
        updateLabel.text = stringCap(f"$rate%.1f/sec")
      }
    }
  }

  lines += (new Label("num items:").styled('fieldName) -> itemsLabel)
  lines += (new Label("updates:").styled('fieldName) -> updateLabel)

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
}

class DefaultDynamicLayerInfoPanel extends DynamicLayerInfoPanel {
  // no additional fields
  setContents
}