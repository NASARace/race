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

/**
  * race-ww specific messages
  */

import akka.actor.ExtendedActorSystem
import gov.nasa.race.core.SingleTypeAkkaSerializer

//--- sync messages - note these are likely to go to remote actors and hence have to serialize

case class ViewChanged (lat: Double,
                        lon: Double,
                        zoom: Double,
                        heading: Double,
                        pitch: Double,
                        roll: Double,
                        hint: String) {
  def this(v: ViewGoal) = this(v.pos.getLatitude.degrees, v.pos.getLongitude.degrees,
                                  v.zoom, v.heading.degrees, v.pitch.degrees, v.roll.degrees,
                                  v.animationHint )
}

class ViewChangedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[ViewChanged](system) {
  override def serialize(v: ViewChanged): Unit = {
    writeDouble(v.lat)
    writeDouble(v.lon)
    writeDouble(v.zoom)
    writeDouble(v.heading)
    writeDouble(v.pitch)
    writeDouble(v.roll)
    writeUTF(v.hint)
  }

  override def deserialize(): ViewChanged = {
    val lat = readDouble()
    val lon = readDouble()
    val zoom = readDouble()
    val heading = readDouble()
    val pitch = readDouble()
    val roll = readDouble()
    val hint = readUTF()

    ViewChanged(lat,lon,zoom,heading,pitch,roll,hint)
  }
}


case class LayerChanged (name: String, enabled: Boolean)

class LayerChangedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[LayerChanged](system) {
  override def serialize(t: LayerChanged): Unit = {
    writeUTF(t.name)
    writeBoolean(t.enabled)
  }

  override def deserialize(): LayerChanged = {
    val name = readUTF()
    val enabled = readBoolean()

    LayerChanged(name,enabled)
  }
}


case class ObjectChanged (name: String, layerName: String, action: String)

class ObjectChangedSerializer (system: ExtendedActorSystem) extends SingleTypeAkkaSerializer[ObjectChanged](system) {
  override def serialize(t: ObjectChanged): Unit = {
    writeUTF(t.name)
    writeUTF(t.layerName)
    writeUTF(t.action)
  }

  override def deserialize(): ObjectChanged = {
    val name = readUTF()
    val layerName = readUTF()
    val action = readUTF()

    ObjectChanged(name,layerName,action)
  }
}