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

import akka.actor.Actor.Receive
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.SourceQueueWithComplete
import com.typesafe.config.Config
import gov.nasa.race.core.{BusEvent, ParentActor, PipedRaceDataClient}
import gov.nasa.race.earth.WindFieldAvailable
import gov.nasa.race.http._
import gov.nasa.race.ifSome
import gov.nasa.race.ui._
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import scalatags.Text

import java.io.File
import scala.collection.mutable.{ArrayBuffer, SortedMap}


/**
 * a CesiumRoute that displays wind fields
 *
 * this is strongly based on https://github.com/RaymanNg/3D-Wind-Field, using shader resources to compute and animate
 * particles that are put in a wind field that is derived from a client-side NetCDF structure.
 * See also https://cesium.com/blog/2019/04/29/gpu-powered-wind/
 *
 * Ultimately, this structure will be computed by this route and transmitted as a JSON object in order to remove
 * client side netcdfjs dependencies and offload client computation
 *
 * TODO - both windFieldEntries and urlMap still need re-org
 */
trait CesiumWindFieldRoute extends CesiumRoute with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient {

  case class WindField (wfa: WindFieldAvailable) {
    val ext = FileUtils.getGzExtension( wfa.file)
    val urlName = f"${wfa.area}-${wfa.wfType}-${wfa.forecastDate.format_yyyyMMdd}-t${wfa.forecastDate.getHour}%02dz.$ext"
    val json = wfa.toJsonWithUrl(s"wind-data/$urlName")
  }

  // we only keep the newest wf for each area/forecast time
  case class WindFieldAreas(date: DateTime, areas: SortedMap[String,WindField])

  protected val windFields: ArrayBuffer[WindFieldAreas] = ArrayBuffer.empty// sorted by forecastDate
  protected var urlMap: Map[String,File] = Map.empty // url-filename -> file. watch out - updated from receiveData and accessed from route

  //--- obtaining and updating wind fields

  override def receiveData: Receive = receiveWindFieldData orElse super.receiveData

  def receiveWindFieldData: Receive = {
    case BusEvent(_,wfa:WindFieldAvailable,_) =>
      ifSome(addWindField(wfa)) { wf=>
        urlMap = urlMap + (wf.urlName -> wfa.file)
        //push( TextMessage(wf.json))
        println(s"@@@ ${wf.json}")
      }
  }

  def addWindField(wfa:WindFieldAvailable): Option[WindField] = {
    val date = wfa.forecastDate
    var i = 0
    while (i < windFields.length) {
      val e = windFields(i)
      if (e.date == date) {
        ifSome(e.areas.get(wfa.area)) { prevWf=>
          if (prevWf.wfa.baseDate > wfa.baseDate) return None // we already had a newer one
        }
        val wf = WindField(wfa)
        e.areas += (wfa.area -> wf) // replace or add
        return Some(wf)

      } else if (e.date > date) {
        val wf = WindField(wfa)
        windFields.insert(i, WindFieldAreas(date, SortedMap( (wfa.area -> wf))))
        return Some(wf)
      }
      i += 1
    }

    val wf = WindField(wfa)
    windFields += WindFieldAreas(date, SortedMap( (wfa.area -> wf)))
    Some(wf)
  }

  //--- websocket

  protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    super.initializeConnection(ctx, queue)
    initializeWindConnection(ctx,queue)
  }

  def initializeWindConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
    val remoteAddr = ctx.remoteAddress
    windFields.foreach{ we=>
      we.areas.foreach( e=> pushTo( remoteAddr, queue, TextMessage(e._2.json)))
    }
  }

  //--- routes

  def windRoute: Route = {
    get {
        pathPrefix("wind-data") {
          extractUnmatchedPath { p =>
            val pathName = p.toString()
            urlMap.get(pathName) match {
              case Some(file) => completeWithFileContent(file)
              case None => complete(StatusCodes.NotFound, pathName)
            }
          }
        } ~
        pathPrefix("wind-particles" ~ Slash) { // this is the client side shader code, not the dynamic wind data
          extractUnmatchedPath { p =>
            val pathName = s"wind-particles/$p"
            complete( ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
          }
        } ~
        fileAssetPath("ui_cesium_wind.js") ~
        fileAssetPath("wind-icon.svg")
    }
  }

  override def route: Route = windRoute ~ super.route

  //--- document content (generated by js module)

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
    extModule("wind-particles/windUtils.js"),
    extModule("wind-particles/particleSystem.js"),
    extModule("wind-particles/particlesComputing.js"),
    extModule("wind-particles/particlesRendering.js"),
    extModule("wind-particles/customPrimitive.js"),
    extModule("ui_cesium_wind.js")
  )

  //--- client config
}

/**
  * a single page application that processes wind channels
  */
class CesiumWindFieldApp(val parent: ParentActor, val config: Config) extends DocumentRoute with CesiumWindFieldRoute with ImageryLayerRoute