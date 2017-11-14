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

import gov.nasa.race._
import gov.nasa.race.{DataStreamReader, DataStreamWriter}
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.track.{ProximityEvent, ProximityReference, TrackedObject, TrackedObjectEnumerator}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Speed._
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer

object SimpleTrackProtocol {
  final val schema = "gov.nasa.race.SimpleTrackProtocol" // TODO - should be a schema definition

    /**
    protocol SimpleTrackProtocol {
        record SimpleTrack {
            string id;
            timestamp_ms time_msec;
            double lat_deg;
            double lon_deg;
            double alt_m;
            double heading_deg;
            double speed_m_sec;
        }

        record TrackMsg {
            int msg_type = 1;
            short n_records;
            array<SimpleTrack> tracks;
        }

        record ProximityChange {
            string ref_id;
            double lat_deg;
            double lon_deg;
            double alt_m;
            double dist_m;
            int    flags;
            SimpleTrack proximity;
        }

        record ProximityMsg {
            int msg_type = 2;
            short n_records;
            array<ProximityChange> proximities;
        }
    }
      **/


  final val TrackMsg: Short = 1
  final val ProximityMsg: Short = 2
}


/**
  * DataStream reader that creates FlightPos objects from native data records of format
  *
  *   NOTE - instances are not thread safe to save memory allocations. The read() method can be called at a high rate
  *   Do not store the result value
  */
class SimpleTrackReader extends DataStreamReader {
  import SimpleTrackProtocol._

  val schema = SimpleTrackProtocol.schema
  val list = new ArrayBuffer[Any] // buffer for results

  def read (dis: DataInputStream): Option[Any] = {
    list.clear
    try {
      dis.readShort match {
        case TrackMsg => readTrackMsg(dis,list)
        case ProximityMsg => readProximityMsg(dis,list)
      }
    } catch {
      case x: Throwable => // ignore the rest
    }
    if (list.isEmpty) None else Some(list)
  }

  def readTrackMsg(dis: DataInputStream, list: ArrayBuffer[Any]) = {
    val nTracks = dis.readShort
    while (list.size < nTracks) list += readTrack(dis)
  }

  def readTrack (dis: DataInputStream): TrackedObject = {
    val id = dis.readUTF
    val timeMsec = dis.readLong
    val latDeg = dis.readDouble
    val lonDeg = dis.readDouble
    val altM = dis.readDouble
    val headingDeg = dis.readDouble
    val speedMS = dis.readDouble

    FlightPos(id, id,
      LatLonPos.fromDegrees(latDeg, lonDeg), Meters(altM),
      MetersPerSecond(speedMS), Degrees(headingDeg),
      new DateTime(timeMsec))
  }

  def readProximityMsg (dis: DataInputStream, list: ArrayBuffer[Any]) = {
    val nProximities = dis.readShort
    while (list.size < nProximities) list += readProximity(dis)
  }

  def readProximity (dis: DataInputStream): ProximityEvent = {
    val refId = dis.readUTF
    val latDeg = dis.readDouble
    val lonDeg = dis.readDouble
    val altM = dis.readDouble
    val distM = dis.readDouble
    val flags = dis.readInt

    val track = readTrack(dis)
    ProximityEvent(ProximityReference(refId, track.date, LatLonPos.fromDegrees(latDeg,lonDeg), Meters(altM)),
                    Meters(distM), flags, track)
  }
}


class SimpleTrackWriter extends DataStreamWriter {
  import SimpleTrackProtocol._

  val schema = SimpleTrackProtocol.schema

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

  def writeProximity (dos: DataOutputStream, p: ProximityEvent) = {
    val ref = p.ref
    val latLonPos = ref.position

    dos.writeUTF(ref.id)
    dos.writeDouble(latLonPos.φ.toDegrees)
    dos.writeDouble(latLonPos.λ.toDegrees)
    dos.writeDouble(ref.altitude.toMeters)

    dos.writeDouble(p.distance.toMeters)
    dos.writeInt(p.flags)

    writeTrack(dos,p.track)
  }

  def write (dos: DataOutputStream, data: Any): Int = {
    data match {
      case t: TrackedObject =>
        dos.writeShort(TrackMsg)
        dos.writeShort(1)  // one track record
        writeTrack(dos,t)
        dos.size // we wrote one record

      case tracks: TrackedObjectEnumerator =>
        val nTracks = tracks.numberOfTrackedObjects
        if (nTracks > 0) {
          dos.writeShort(TrackMsg)
          dos.writeShort(nTracks)
          tracks.foreachTrackedObject(writeTrack(dos,_))
          dos.size
        } else 0

      case p: ProximityEvent =>
        dos.writeShort(ProximityMsg)
        dos.writeShort(1) // one proximity record
        writeProximity(dos,p)
        dos.size

      case _ => 0 // all others we don't know about
    }
  }
}
