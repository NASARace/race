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

package gov.nasa.race.air

import java.io.{DataInputStream, DataOutputStream}

import gov.nasa.race.{DataStreamReader, DataStreamWriter}
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed._
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * DataStream reader that creates FlightPos objects from native data records of format
  *
  *   struct simple_track {
  *     char   *id;
  *     long   time_msec;
  *     double lat_deg;
  *     double lon_deg;
  *     double alt_m;
  *     double heading_deg;
  *     double speed_m_sec;
  *   }
  *
  *   NOTE - instances are not thread safe to save memory allocations. The read() method can be called at a high rate
  *   Do not store the result value
  */
class SimpleTrackReader extends DataStreamReader {
  val list = new ArrayBuffer[Any] // buffer for results

  val schema = "simple_track"  // the supported schema

  def read (dis: DataInputStream): Option[Any] = {
    list.clear
    try {
      var nTracks = dis.readShort

      while (dis.available > 0 && list.size < nTracks) {
        val id = dis.readUTF
        val timeMsec = dis.readLong
        val latDeg = dis.readDouble
        val lonDeg = dis.readDouble
        val altM = dis.readDouble
        val headingDeg = dis.readDouble
        val speedMS = dis.readDouble

        val fpos = FlightPos(id,id,
                             LatLonPos.fromDegrees(latDeg,lonDeg),Meters(altM),
                             MetersPerSecond(speedMS),Degrees(headingDeg),
                             new DateTime(timeMsec))
        list += fpos
      }

    } catch {
      case x: Throwable => // ignore the rest
    }
    if (list.isEmpty) None else Some(list)
  }
}


class SimpleTrackWriter extends DataStreamWriter {

  val schema = "simple_track"  // the supported schema

  def writeTrack (dos: DataOutputStream, t: TrackedObject) = {
    val latLonPos = t.position

    dos.writeUTF(t.cs)
    dos.writeLong(t.date.getMillis)
    dos.writeDouble(latLonPos.φ.toDegrees)
    dos.writeDouble(latLonPos.λ.toDegrees)
    dos.writeDouble(t.altitude.toMeters)
    dos.writeDouble(t.heading.toDegrees)
    dos.writeDouble(t.speed.toMetersPerSecond)
  }

  def write (dos: DataOutputStream, data: Any): Int = {
    data match {
      case t: TrackedObject =>
        dos.writeShort(1)  // one track
        writeTrack(dos,t)
        dos.size // we wrote one record

      case list: Seq[_] =>
        if (list.nonEmpty) {
          dos.writeShort(list.size.toShort)
          list foreach {
            case t: TrackedObject => writeTrack(dos, t)
            case _ => // ignore
          }
          dos.size
        } else 0

      case _ => 0 // all others we don't know about
    }
  }
}
