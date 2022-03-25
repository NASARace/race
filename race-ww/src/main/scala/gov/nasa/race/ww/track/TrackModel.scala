/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.ww.track

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.track.Tracked3dObject
import gov.nasa.race.util.StringUtils
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww._
import gov.nasa.worldwind.render.DrawContext
import osm.map.worldwind.gl.obj.{ObjLoader, ObjRenderable}

case class ModelSpec (src: String, size0: Double, pitch0: Double, yaw0: Double, roll0: Double)

object TrackModel {
  //val defaultSpec = ModelSpec("/gov/nasa/race/ww/GlobalHawk.obj.gz", 100, 180,0,0)
  val defaultSpec = ModelSpec("/gov/nasa/race/ww/airplane1.obj", 100, -90,0,0)
  var map = Map("default" -> defaultSpec)

  def getModelSpec (key: String): Option[ModelSpec] = map.get(key)

  def addDefaultModel( key: String, spec: ModelSpec) = map = map + (key -> spec)

  // this can be overridden by concrete FlightLayers to add generic models
  def loadModel[T <: Tracked3dObject](mc: Config): TrackModel[T] = {
    var src = mc.getString("source")
    var size0 = 1.0
    var pitch0,yaw0,roll0 = 0.0

    map.get(src) match {
      case Some(spec) =>
        src = spec.src
        size0 = mc.getDoubleOrElse("size", spec.size0)
        pitch0 = mc.getDoubleOrElse("pitch", spec.pitch0)
        yaw0 = mc.getDoubleOrElse("yaw", spec.yaw0)
        roll0 = mc.getDoubleOrElse("roll", spec.roll0)
      case None =>
        size0 = mc.getDoubleOrElse("size", size0)
        pitch0 = mc.getDoubleOrElse("pitch", pitch0)
        yaw0 = mc.getDoubleOrElse("yaw", yaw0)
        roll0 = mc.getDoubleOrElse("roll", roll0)
    }

    new TrackModel[T](mc.getString("key"), src, size0, pitch0, yaw0, roll0)
  }

  def loadDefaultModel[T <: Tracked3dObject]: TrackModel[T] = new TrackModel[T](defaultSpec)
}

/**
  * Renderable representing a 3D model
  *
  * Note this is from a WorldWindJava extension (see this thread:
  * https://forum.worldwindcentral.com/forum/world-wind-java-forums/development-help/17605-collada-models-with-lighting/page2)
  *
  * Note also that the ObjRenderable does not load the model data before it is rendered, and it does
  * prioritize class resources over files. This means we don't know if the object will render
  * correctly at the time it is constructed, hence we have to fall back to a default placeholder.
  * This might be changed at some point in WorldWindJava-pcm (which includes the ObjRenderable)
  */
class TrackModel[T <: Tracked3dObject](pattern: String, src: String,
                                       var size0: Double,
                                       var yaw0: Double, var pitch0: Double, var roll0: Double)
                        extends ObjRenderable(FarAway,src) {

  def this (ms: ModelSpec) = this("", ms.src, ms.size0, ms.pitch0, ms.yaw0, ms.roll0)

  val regex = StringUtils.globToRegex(pattern)
  var assignedEntry: Option[TrackEntry[T]] = None
  var disable = false

  setSize(size0)
  setAttitude(pitch0,yaw0,roll0)

  //--- initial transformations
  def setAttitude(pitch: Double, yaw: Double, roll: Double) = {
    setElevation(pitch0)
    setAzimuth(yaw0)
    setRoll(roll0)
  }

  def matches(fpos: T): Boolean = !assignedEntry.isDefined && StringUtils.matches(fpos.cs,regex)

  def assign (e: TrackEntry[T]) = assignedEntry = Some(e)
  def unAssign = assignedEntry = None
  def isAssigned = assignedEntry.isDefined

  def update (fpos: T) = {
    setAzimuth(yaw0 + fpos.heading.toDegrees)
    setPosition(fpos)
  }

  protected override def getModel(dc: DrawContext): ObjLoader = {
    try {
      super.getModel(dc)
    } catch {
      case t: Throwable =>
        if (src == modelSource) {
          // retry with default model parameters
          val spec = TrackModel.defaultSpec
          modelSource = spec.src
          size0 = spec.size0
          pitch0 = spec.pitch0
          yaw0 = spec.yaw0
          roll0 = spec.roll0

          setSize(size0)
          setElevation(pitch0 + getElevation)
          setAzimuth(yaw0 + getAzimuth)
          setRoll(roll0 + getRoll)

          super.getModel(dc) // try again

        } else {
          disable = true
          throw new RuntimeException("Error loading FlightModel", t);
        }
    }
  }

  protected override def drawGL(dc: DrawContext): Unit = {
    if (!disable) super.drawGL(dc)
  }
}
