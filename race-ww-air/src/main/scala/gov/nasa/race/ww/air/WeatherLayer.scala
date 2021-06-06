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

import com.typesafe.config.Config
import gov.nasa.race.air.{ItwsGridProjection, PrecipImage}
import gov.nasa.race.core.BusEvent
import gov.nasa.race.swing.{MultiSelection, MultiSelectionPanel}
import gov.nasa.race.swing.Style._
import gov.nasa.race.uom.Length._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww.{DynamicLayerInfoPanel, RaceViewer, SubscribingRaceLayer}
import gov.nasa.worldwind.geom.LatLon
import gov.nasa.worldwind.render.{Renderable, SurfaceImage}

import scala.collection.Seq
import scala.collection.immutable.{ArraySeq, SortedMap}
import scala.jdk.CollectionConverters._
import scala.collection.mutable.{Map => MutableMap}

object WeatherLayer {

  class PrecipEntry (val pi: PrecipImage)
              extends SurfaceImage(pi.img, ArraySeq.unsafeWrapArray(computeGridCorners(pi)).asJava) {
    def update (newPi: PrecipImage) = setImageSource(newPi.img, corners)
  }

  def computeGridCorners(pi: PrecipImage): Array[LatLon] = {
    val proj = new ItwsGridProjection(pi.trpPos, pi.xoffset, pi.yoffset, pi.rotation)
    // order is sw,se,ne,nw
    Array[LatLon]( latLonPos2LatLon( proj.toLatLonPos(Length0, Length0)),
                   latLonPos2LatLon( proj.toLatLonPos(pi.width, Length0)),
                   latLonPos2LatLon( proj.toLatLonPos(pi.width, pi.height)),
                   latLonPos2LatLon( proj.toLatLonPos(Length0, pi.height)))
  }

  case class ItwsProduct (val id: String, val descr: String)

  // order represents image Z-order (last on top)
  val allProducts = SortedMap[String,String](
    "9905" -> "long range precipitation",
    "9850" -> "TRACON precipitation",
    "9849" -> "5nm precipitation"
  )
}
import gov.nasa.race.ww.air.WeatherLayer._

/**
 * a WorldWind layer to display weather data
 */
class WeatherLayer (val raceViewer: RaceViewer, val config: Config) extends SubscribingRaceLayer {

  // this map is used to update images in const time, without having to iterate over all renderables
  val precipMap = MutableMap[String,PrecipEntry]()

  var selProducts: Seq[String] = config.getOptionalStringList("request-topics")

  val panel = new DynamicLayerInfoPanel(this).styled("consolePanel")
  val selPanel = new MultiSelectionPanel[String](
    "product:", "select product",
    allProducts.keys.toSeq, selProducts, s => s, allProducts(_), allProducts.size
  )(selectProducts).defaultStyled
  panel.contents.addOne(selPanel)

  override def size = precipMap.size

  override def initializeLayer: Unit = {
    super.initializeLayer
    selProducts.foreach(s => requestTopic(Some(s)))
  }

  def selectProducts (res: MultiSelection.Result[String]): Unit = {
    def selProd (newSel: Seq[String]): Unit = {
      selProducts.diff(newSel).foreach { s => // filter the old products that are released
        precipMap.filterInPlace { (k, v) =>
          if (v.pi.product == s) {
            removeRenderable(v)
            false
          } else true
        }
        releaseTopic(Some(s))
      }
      newSel.diff(selProducts).foreach {  // request the new products
        s => requestTopic(Some(s))
      }
      selProducts = newSel
    }

    res match {
      case MultiSelection.SomeSelected(newSel) => selProd(newSel)
      case MultiSelection.NoneSelected(_) => selProd(Seq.empty[String])
      case MultiSelection.AllSelected(allProds) => selProd(allProds)
      case MultiSelection.Canceled(_) => // do nothing
    }
  }

  def sortInPERenderable (peNew: PrecipEntry): Unit = {
    val prod = peNew.pi.product
    sortInRenderable(peNew){ (r, _) =>
      r match {
        case pe: PrecipEntry => pe.pi.product.compare(prod)
        case _ => -1
      }
    }
  }

  def dumpRenderables: Unit = {
    println("----------------")
    renderables.asScala.foreach(e=>println(e.asInstanceOf[PrecipEntry].pi))
  }

  override def handleMessage = {
    case BusEvent(_,pi:PrecipImage,_) =>
      updateCount = updateCount+1

      // only add/keep images that have precipitation
      precipMap.get(pi.id) match {
        case Some(pe: PrecipEntry) =>
          if (pi.maxPrecipLevel > 0) {
            pe.update(pi)
          } else {
            removeRenderable(pe)
            precipMap -= pi.id
          }

        case None =>
          if (pi.maxPrecipLevel > 0) {
            val pe = new PrecipEntry(pi)
            sortInPERenderable(pe)
            precipMap += (pi.id -> pe)
          }
      }

      wwdRedrawManager.redraw()

    case other => info(f"$name ignoring message $other%30.30s..")
  }
}
