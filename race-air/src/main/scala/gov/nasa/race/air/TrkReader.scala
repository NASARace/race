/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import java.io.DataInputStream

import gov.nasa.race.common.DataStreamReader
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.repeat
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Speed.MetersPerSecond
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * DataStreamReader for binary TRK messages in network byte order (big endian):
  *
  * packed struct TRK {
  *   ['T','R','K'];  // magic bytes
  *   u8 nTracks;
  *   struct track[nTracks];
  * }
  *
  * packed struct track {
  *   u8 idLen
  *   char id[idLen];
  *   i64 date;  // epoch millis
  *   f64 lat;   // deg
  *   f64 lon;   // deg
  *   f64 alt;   // m
  *   f32 hdg;   // deg  (true)
  *   f32 spd;   // m/sec (ground)
  *   f32 vr;    // m/sec
  *   f32 phi;   // deg
  *   f32 theta; // deg
  *   f32 psi;   // deg
  *   u32 status;
  * }
  *
  * Note - this implementation is not threadsafe (it avoids memory allocation)
  */
class TrkReader extends DataStreamReader {
  val tracks = new ArrayBuffer[TrackedAircraft](256)  // for parsed tracks
  val buf = new Array[Byte](256) // for String parsing (max 256 chars)
  val magic = Array[Byte]('T','R','K')

  override def read(dis: DataInputStream): Option[Any] = {
    tracks.clear()
    if (matchBytes(dis,magic)) {
      val nTracks = dis.readUnsignedByte
      repeat(nTracks) {
        val id = readString256(dis,buf)
        val date = DateTime.ofEpochMillis(dis.readLong)
        val lat = Degrees(dis.readDouble)
        val lon = Degrees(dis.readDouble)
        val alt = Meters(dis.readDouble)
        val hdg = Degrees(dis.readFloat)
        val spd = MetersPerSecond(dis.readFloat)
        val vr = MetersPerSecond(dis.readFloat)
        val phi = Degrees(dis.readFloat)
        val theta = Degrees(dis.readFloat)
        val psi = Degrees(dis.readFloat)
        val status = dis.readInt

        val track = new ExtendedFlightPos(id,id, GeoPosition(lat,lon,alt), spd,hdg,vr, date,status, theta,phi,"?")
        tracks += track
      }
      Some(tracks)
    } else None
  }

  override val schema: String = "TRK"
}
