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

import java.io.{DataInputStream, DataOutputStream}

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.Tracked3dObject
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.Speed.MetersPerSecond
import gov.nasa.race.uom.DateTime

object ExtendedTrackProtocol {
  final val schema = "gov.nasa.race.air.ExtendedTrackProtocol" // TODO - should be a schema definition

  /**
      record ExtendedTrack {
        <SimpleTrack>
        double pitchDeg
        double rollDeg
        string trackType
      }
      record TrackMsg {
        int msg_type = 1;
        short n_records;
        array<ExtendedTrack> tracks;
      }
  **/
}

class ExtendedTrackReader extends SimpleTrackReader {
  override val schema = ExtendedTrackProtocol.schema

  override def readTrack (dis: DataInputStream): Tracked3dObject = {
    val id = dis.readUTF.intern // we likely get lots of messages for the same aircraft, avoid tons of strings
    val msgOrd = dis.readInt  // can be used to check consistency
    val flags = dis.readInt
    val timeMsec = dis.readLong
    val latDeg = dis.readDouble
    val lonDeg = dis.readDouble
    val altM = dis.readDouble
    val speedMS = dis.readDouble
    val headingDeg = dis.readDouble
    val vrMS = dis.readDouble

    val pitchDeg = dis.readDouble
    val rollDeg = dis.readDouble
    val trackType = dis.readUTF.intern

    new ExtendedFlightPos(id, id,
      GeoPosition( Degrees(latDeg), Degrees(lonDeg), Meters(altM)),
      MetersPerSecond(speedMS), Degrees(headingDeg), MetersPerSecond(vrMS),
      DateTime.ofEpochMillis(timeMsec),flags,
      Degrees(pitchDeg), Degrees(rollDeg), trackType)
  }
}

class ExtendedTrackWriter extends SimpleTrackWriter {
  override val schema = ExtendedTrackProtocol.schema

  override def writeTrack (dos: DataOutputStream, t: Tracked3dObject) = {
    super.writeTrack(dos,t)

    t match {
      case et: ExtendedFlightPos =>
        dos.writeDouble(et.pitch.toDegrees)
        dos.writeDouble(et.roll.toDegrees)

      case _ => // we just send 0's for the extra fields
        dos.writeDouble(0)
        dos.writeDouble(0)
    }
  }
}