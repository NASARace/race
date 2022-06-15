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
package gov.nasa.race.air

import gov.nasa.race.{Failure, ResultValue, SuccessValue}
import gov.nasa.race.common.Utf8CsvPullParser
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Feet
import gov.nasa.race.uom.Speed.{FeetPerMinute, Knots}
import gov.nasa.race.uom.{Angle, DateTime, Speed}

/**
  * track that is generated from Sherlock IFF track points
  * (see https://sherlock.opendata.arc.nasa.gov/api/docs/#/products/IFF)
  *
  * TODO - this still needs to capture more data from IFF records
  */
case class IffTrack (
                     id: String,
                     cs: String,
                     position: GeoPosition,
                     speed: Speed,
                     heading: Angle,
                     vr: Speed,
                     date: DateTime,
                     status: Int,

                     //--- IFF specific
                     src: String
                     // ... and more to follow
                    ) extends TrackedAircraft

/**
  *  3,1597856403.691,23792,3305,432,FAT,AIG200,N3774H,1,36.61782,-119.75995,90.00,10,0.224,0.224,,154,133,0,,,0,,,,,,,,,,,,,,,,,,a450b8,,,,,
  *
  *    1  recType = 3	3	Track point record type number. This indicates the record in the csv file is a track point.
  *    2  recTime	1452905537.000	Time of the track point record from ATC automation in Seconds since midnight 1/1/70 UTC
  *    3  fltKey	15687	Integer assigned to a single flight by Sherlock processing. It is unique per facility per day and across a file set. It can be used to join with the ‘Msn’ field in the RD and EV files.
  *    4  bcnCode		4-digit beacon code, with valid digit range from 0 to 7 (‑999 for unknown)
  *    5  cid	135	Computer flight id assigned by ATC automation system
  *    6  Source	0/ZOB	Source of the track point record– for certain ATC automation systems with multiple sensors, the sensor number precedes the ‘/’
  *    7  msgType	TH	String representing the type of the original message
  *    8  acId	JBU1013	Aircraft ID (call sign)
  *    9  recTypeCat	1	Track point record category (see above for record type categories description, the possible values are 1 to 6)
  *   10  coord1	43.04972	First location coordinate (by default, latitude) – could be x if Cartesian system is used
  *   11  coord2	-83.78306	Second location coordinate (by default, longitude) – could be y if Cartesian system is used
  *   12  alt	360.00	Altitude (by default, in 100s of feet)
  *   13  significance	10	Significance of the track point. A score indicating the relative importance of the track point in defining the trajectory. Signifcance 1 is most important whereas 10 is least important. For use in data reduction techniques.
  *   14  coord1Accur	100.000	The accuracy of ‘coord1’ (in ‘coord1’s units)
  *   15  coord2Accur	100.000	The accuracy of ‘coord2’ (in ‘coord2’s units)
  *   16  altAccur		Altitude accuracy (in ‘alt’s units)
  *   17  groundSpeed	400	Ground speed (by default, in knots) -99 if unknown
  *   18  course	264	Course (by default, in degrees from true north) -99 if unknown
  *   19  rateOfClimb	-167	Rate of climb (by default, in feet per minute) -99999 if unknown
  *   20  altQualifier	C	Altitude qualifier (the “B4 character”, controllers full data block used for tracking an aircraft has a special indicator for the B4 character)
  *   21  altIndicator		Altitude indicator (the “C4 character”, controllers full data block used for tracking an aircraft has a special indicator for the C4 character)
  *   22  trackPtStatus		Track point status (e.g., ‘C’ for coast, the aircraft track is in coast)
  *   23  leaderDir		Leader direction (an integer from 0-7 representing the direction of the leader line) from Common ARTS. 0 = North, 1 = Northeast, 2 = East, 3 = Southeast, 4 = South, 5 = Southwest, 6 = West, 7 NorthWest.
  *   24  scratchPad		Scratch pad – contains notations from the controller entered into the automation system
  *   25  msawInhibitInd		Minimum Safe Altitude Warning Inhibit Indicator (0=MSAW not inhibited, 1= MSAW inhibited)
  *   26  assignedAltString	360	Assigned altitude string from the original message.
  *   27  controllingFac	ZOB	The controlling facility (the facility that is controlling the flight). - from Center Common Message Set data
  *   28  controllingSec	18	The controlling sector (the sector/position that is controlling the flight). number or controller ID - from CMS data
  *   29  receivingFac		The receiving (the facility that the flight is being handed off to)facility - from CMS data
  *   30  receivingSec	ZAU	The receiving sector number or controller ID - from CMS data
  *   31  activeContr	23	The active (i.e., controlling) controller number - from Common ARTS data
  *   32  primaryContr		The primary (i.e., previous, controlling, or possible next) controller number - from Common ARTS data
  *   33  kybrdSubset		Keyboard subset (identifies a subset of controller keyboards) – from Common ARTS and ARTS 3A data
  *   34  kybrdSymbol		Keyboard symbol (identifies a keyboard within the keyboard subset) – from Common ARTS and ARTS 3A data
  *   35  adsCode		Arrival Departure Status Code - from Common ARTS and ARTS 3A data – an integer used by these automation systems to map to airports within their airpace.
  *   36  opsType		Operations type (O/E/A/D/I/U) = Overflight/Enroute/Arrival/Departure/Intraflight/Unknown
  *   37  airportCode		Airport Code - from Common ARTS and ARTS 3A data
  *   38  trackNumber		Track number, automation system internal tracking number
  *   39  tptReturnType		Trackpoint return type
  *   40  modeSCode		Mode S Code– transponder code unique to the aircraft, also referred to as ICAO address or aircraft address.
  *   41  sensorTrack NumberList		A list of sensor/track number combinations (each sensor assigned a set of track number from Common ARTS data)
  *   42  spi		Something representing the "Ident feature"
  *   43  dvs		Indicates the aircraft is within a suppression volume area
  *   44  dupM3a		Indicates 2 aircraft have the same Mode 3A code
  *   45  tid		Aircraft Identifier entered by pilot
  */
trait IffTrackPointParser extends Utf8CsvPullParser {

  // is thie a tailNr or a regular airline callsign?
  def isCS (acId: CharSequence): Boolean = {
    var i = 0
    while (i<acId.length()) {
      if (acId.charAt(i) > '9') return true
      i += 1
    }
    false
  }

  // this assumes the recType has already been parsed and this is a trackpoint record (type 3)
  def parseTrackPoint(): ResultValue[IffTrack] = {
    val date = DateTime.ofEpochFractionalSeconds(readNextValue().toDouble)
    val id = readNextValue().intern
    skip(2) // bcnCode, cid
    val src = readNextValue().intern
    skip(1) // msgType
    val acId = readNextValue()
    val cs = if (isCS(acId)) acId.intern else id
    skip(1) // recTypeCat
    val lat = Degrees(readNextValue().toDouble)
    val lon = Degrees(readNextValue().toDouble)
    val alt = Feet(readNextValue().toDouble*100)
    skip(4)
    val spd = Knots(readNextValue().toDouble)

    val course = readNextValue()
    val hdg = if (course.isEmpty) Angle.UndefinedAngle else Degrees(course.toDouble)

    val rateOfClimb = readNextValue()
    val vr = if (rateOfClimb.isEmpty) Speed.UndefinedSpeed else FeetPerMinute(rateOfClimb.toDouble)
    // the rest we don't care about
    skipToEndOfRecord()

    // nothing in the IFF record itself that tells us it is new or completed
    // we have to get that from type 2 (header) records
    val status = 0

    if (date.isDefined && cs.nonEmpty && lat.isDefined && lon.isDefined && alt.isDefined) {
      SuccessValue(new IffTrack(id,cs,GeoPosition(lat,lon,alt),spd,hdg,vr,date, status, src))
    } else {
      Failure("insufficient data")
    }
  }
}
