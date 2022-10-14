/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.cesium

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http._
import gov.nasa.race.ui.{extModule, uiCheckBox, uiIcon, uiList, uiPanel, uiRowContainer, uiWindow}
import gov.nasa.race.uom.DateTime
import scalatags.Text

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.SeqMap

case class CesiumLayer (name: String, url: String, file: Option[File], show: Boolean, preFetch: Boolean = true) { //.. probably more attributes to follow
  def date = file.map( f=> DateTime.ofEpochMillis(f.lastModified())).getOrElse(DateTime.UndefinedDateTime)
}

/**
  * a CesiumRoute that pushes static file updates containing layers (e.g. kml)
  */
trait CesiumLayerRoute extends QueryProxyRoute with FSCachedProxyRoute with CesiumRoute with PushWSRaceRoute with CachedFileAssetRoute {

  //--- init layers

  val layers: mutable.SeqMap[String,CesiumLayer] = config.getConfigSeq("layers").foldLeft(SeqMap.empty[String,CesiumLayer]) { (map, layerConf) =>
    val name = layerConf.getString("name")
    val url = layerConf.getString("url")
    val file = getFileFromRequestUri(url)
    val show = layerConf.getBooleanOrElse("show", false)
    map += name -> CesiumLayer(name,url,file,show)
  }

  override def onRaceStarted(server: HttpServer): Boolean = {
    layers.values.foreach { layer =>
      if (layer.preFetch) {
        getFileFromRequestUri(layer.url) match {
          case Some(file) => fetchFile(file, layer.url){
            info(s"retrieved: ${layer.url}")
          }
          case None => // ignore
        }
      }
    }
    super.onRaceStarted(server)
  }

  def layerMessage(layer: CesiumLayer): TextMessage.Strict = {
    val msg = s"""{"layer":{"name":"${layer.name}","date":${layer.date.toEpochMillis},"url":"${layer.url}","show":${layer.show}}}"""
    TextMessage.Strict(msg)
  }

  //--- routes

  def layerRoute: Route = {
    get {
      path ("proxy") {
        completeProxied // the request url is encoded in the query
      } ~
        fileAssetPath("ui_cesium_layers.js") ~
        fileAssetPath("layer-icon.svg")
    }
  }

  override def route: Route = layerRoute ~ super.route

  //--- document content

  def uiLayerWindow(title: String="Layers"): Text.TypedTag[String] = {
    uiWindow(title, "layers", "layer-icon.svg")(
      uiList("layers.list", 10, "main.selectLayer(event)"),
      uiPanel("layer parameters", false)()
    )
  }
  def uiLayerIcon: Text.TypedTag[String] = {
    uiIcon("layer-icon.svg", "main.toggleWindow(event,'layers')", "layer_icon")
  }

  def uiCesiumLayerResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule("ui_cesium_layers.js"))
  }

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiCesiumLayerResources

  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiLayerWindow(), uiLayerIcon)

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeLayerConnection(ctx,queue)
  }

  def initializeLayerConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.remoteAddress
    layers.values.foreach{ layer=>
      pushTo( remoteAddr, queue, layerMessage(layer))
    }
  }
}


/**
  * a single page application that processes track channels
  */
class CesiumLayerApp (val parent: ParentActor, val config: Config) extends DocumentRoute with CesiumLayerRoute
