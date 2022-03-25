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

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track._
import gov.nasa.race.common.{DataStreamReader,DataStreamWriter}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer

object SimpleTrackProtocol {
  final val schema = "gov.nasa.race.air.SimpleTrackProtocol" // TODO - should be a schema definition

    /**
    protocol SimpleTrackProtocol {
        record SimpleTrack {
            string id;
            int msg_ordinal; // starting with 1
            int flags;       // completion etc

            timestamp_ms time_msec;
            double lat_deg;
            double lon_deg;
            double alt_m;
            double speed_m_sec;
            double heading_deg;
            double vr_m_sec;    // vertical rate
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

        record DroppedTrack {
            string id;
            int flags;
            timestamp_ms time_millis;
        }
        record DropMsg {
            int msg_type = 3;
            short n_records;
            array<DroppedTrack> drops;
        }
    }
      **/

  //--- data message types
  final val TrackMsg: Short = 1
  final val ProximityMsg: Short = 2
  final val DropMsg: Short = 3

  //--- track msg flags see TrackedObject

  //--- proximity flags
  final val ProxNew: Int        = 0x01
  final val ProxChange: Int     = 0x02
  final val ProxDrop: Int       = 0x04
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
    list.clear()
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

  def readTrack (dis: DataInputStream): Tracked3dObject = {
    val id = dis.readUTF
    val msgOrd = dis.readInt  // can be used to check consistency
    val flags = dis.readInt
    val timeMsec = dis.readLong
    val latDeg = dis.readDouble
    val lonDeg = dis.readDouble
    val altM = dis.readDouble
    val speedMS = dis.readDouble
    val headingDeg = dis.readDouble
    val vrMS = dis.readDouble

    new FlightPos(id, id,
      GeoPosition.fromDegreesAndMeters(latDeg, lonDeg, altM),
      MetersPerSecond(speedMS), Degrees(headingDeg), MetersPerSecond(vrMS),
      DateTime.ofEpochMillis(timeMsec),flags)
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

    val trackId = dis.readUTF
    val tmsec = dis.readLong
    val trackLatDeg = dis.readDouble
    val trackLonDeg = dis.readDouble
    val trackAltM = dis.readDouble
    val trackSpdMS = dis.readDouble
    val trackHdgDeg = dis.readDouble
    val trackVrMS = dis.readDouble


    val track = new FlightPos(trackId,trackId,GeoPosition(Degrees(trackLatDeg),Degrees(trackLonDeg),Meters(trackAltM)),
                              MetersPerSecond(trackSpdMS),Degrees(trackHdgDeg),MetersPerSecond(trackVrMS),
                              DateTime.ofEpochMillis(tmsec))

    // we just use refId to identify the event - if clients need unique ids they have to re-identify
    ProximityEvent(refId,"proximity",ProximityReference(refId, track.date, GeoPosition.fromDegreesAndMeters(latDeg,lonDeg,altM)),
                    Meters(distM), flags, track)
  }

  def readDropMsg (dis: DataInputStream, list: ArrayBuffer[Any]) = {
    val nDrops = dis.readShort
    while (list.size < nDrops) list += readDrop(dis)
  }

  def readDrop (dis: DataInputStream): TrackTerminationMessage = {
    val id = dis.readUTF
    val flags = dis.readInt // TODO - we should use this to determine the type of drop
    val timeMsec = dis.readLong
    TrackDropped(id,id,DateTime.ofEpochMillis(timeMsec))
  }
}


class SimpleTrackWriter extends DataStreamWriter {
  import SimpleTrackProtocol._

  val schema = SimpleTrackProtocol.schema

  def writeTrack (dos: DataOutputStream, t: Tracked3dObject) = {
    val latLonPos = t.position

    val msgOrd = 0 // TODO - needs to be passed in or added to TrackedObject

    dos.writeUTF(t.cs)
    dos.writeInt(msgOrd)
    dos.writeInt(t.status)
    dos.writeLong(t.date.toEpochMillis)
    dos.writeDouble(latLonPos.φ.toDegrees)
    dos.writeDouble(latLonPos.λ.toDegrees)
    dos.writeDouble(latLonPos.altitude.toMeters)
    dos.writeDouble(t.speed.toMetersPerSecond)
    dos.writeDouble(t.heading.toDegrees)
    dos.writeDouble(t.vr.toMetersPerSecond)
  }

  def writeProximity (dos: DataOutputStream, p: ProximityEvent) = {
    val ref = p.ref
    var pos = ref.position

    dos.writeUTF(ref.id)
    dos.writeDouble(pos.φ.toDegrees)
    dos.writeDouble(pos.λ.toDegrees)
    dos.writeDouble(pos.altitude.toMeters)

    dos.writeDouble(p.distance.toMeters)
    dos.writeInt(p.status)

    val prox = p.track
    pos = prox.position

    dos.writeUTF(prox.cs)
    dos.writeLong(prox.date.toEpochMillis)
    dos.writeDouble(pos.φ.toDegrees)
    dos.writeDouble(pos.λ.toDegrees)
    dos.writeDouble(pos.altitude.toMeters)
    dos.writeDouble(prox.speed.toMetersPerSecond)
    dos.writeDouble(prox.heading.toDegrees)
    dos.writeDouble(prox.vr.toMetersPerSecond)
  }

  def writeDrop (dos: DataOutputStream, drop: TrackTerminationMessage) = {
    val flags = 0 // TODO - we should determine this based on the concrete TrackTermination type

    dos.writeUTF(drop.cs)
    dos.writeInt(flags)
    dos.writeLong(drop.date.toEpochMillis)
  }

  def write (dos: DataOutputStream, data: Any): Int = {
    data match {

      case p: ProximityEvent =>  // watch out - this is also a TrackedObject
        dos.writeShort(ProximityMsg)
        dos.writeShort(1) // one proximity record
        writeProximity(dos,p)
        dos.size

      case t: Tracked3dObject =>
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

      case drop: TrackTerminationMessage =>
        dos.writeShort(DropMsg)
        dos.writeShort(1)
        writeDrop(dos,drop)
        dos.size

        // TODO - we probably also need a DropEventEnumerator

      case _ => 0 // all others we don't know about
    }
  }
}
