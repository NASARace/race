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
package gov.nasa.race.air

import java.io.{OutputStream, PrintStream}

import com.typesafe.config.Config
import gov.nasa.race.archive.{ArchiveWriter, CSVArchiveWriter}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createOutputStream}
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}

object TATrack {
  // status bit flags for TATrack attrs (should be >0xffff)

  final val PseudoFlag     = 0x10000
  final val AdsbFlag       = 0x20000
  final val CoastingFlag   = 0x40000

  object Status extends Enumeration {
    type Status = Value
    val Active,Coasting,Drop,Undefined = Value
  }
}

/**
  * a specialized TrackedAircraft that represents tracks from TAIS/STARS messages
  */
case class TATrack (id: String,
                    cs: String,
                    position: GeoPosition,
                    heading: Angle,
                    speed: Speed,
                    vr: Speed,
                    date: DateTime,
                    status: Int,

                    src: String,
                    xyPos: XYPos,
                    beaconCode: String,
                    flightPlan: Option[FlightPlan]
                  ) extends TrackedAircraft {
  import TATrack._

  override def toString = {
    f"TATrack($src,$id,$cs,0x${status.toHexString},$position,${heading.toDegrees}%.0f°,${speed.toKnots}%.1fkn,${vr.toFeetPerMinute}%.0f, $date, $flightPlan)"
  }

  override def source: Option[String] = Some(src)

  def isPseudo = (status & PseudoFlag) != 0
  def isAdsb = (status & AdsbFlag) != 0

  def hasFlightPlan = flightPlan.isDefined
}


/**
  * an ArchiveWriter for a collection of TATracks that were received/should be replayed together
  * (normally corresponding to a TATrackAndFlightPlan XML message)
  */
class TATrackBulkArchiveWriter(val oStream: OutputStream, val pathName: String="<unknown>") extends CSVArchiveWriter {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))


  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case it: Iterable[TATrack] => writeTracks(date, it)
      case _ => false
    }
  }

  def writeTracks (date: DateTime, tracks: Iterable[TATrack]): Boolean = {
    if (tracks.nonEmpty) {
      var src: String = null

      tracks.foreach { t=>
        if (src == null) {
          src = t.src
          writeHeader(date,src)
        }
        writeTrack(t)
      }

      true
    } else false // nothing written
  }

  def writeHeader (date: DateTime, src: String): Unit = {
    ps.print("--- ")
    ps.print(src)
    ps.print(' ')
    ps.println(date.toEpochMillis)  // receive/replay date -- TODO: add optional offset
  }

  def writeTrack (t: TATrack): Unit = {
    printString(t.id)
    printString(t.cs)  // TODO: add optional obfuscation
    printString(t.position)
    printString(t.heading)
    printString(t.speed)
    printString(t.vr)
    printString(t.date)
    printString(t.status)
    // we don't need the src since it is in the header
    printXYPos(t.xyPos)
    printString(t.beaconCode, recSep)
    // TODO: add flightPlan

  }
}