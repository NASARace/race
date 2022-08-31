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
package gov.nasa.race.space

import gov.nasa.race.common._
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.uom.Time.Days
import gov.nasa.race.util.SubstringParser

import java.time.ZonedDateTime
import java.time.temporal.ChronoField

/**
  * class that represents Two Line Element (TLE) orbit specifications as defined on
  *   https://spaceflight.nasa.gov/realdata/sightings/SSapplications/Post/JavaSSOP/SSOP_Help/tle_def.html
  *
  * example:
  *   ISS (ZARYA)
  *   1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927
  *   2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537
  *
  *             1         2         3         4         5         6
  *   0123456789012345678901234567890123456789012345678901234567890123456789   column
  *   .         .         .         .         .         .         .
  *   1 2----3 4-5--6-- 7-8----------- 9--------- 10------ 11------   13--     line 1
  *   .         .         .         .         .         .         .
  *   1 2---- 3------- 4------- 5------ 6------- 7------- 8----------9----     line 2
  *
  */
object TLE {

  //3LE based based ctor
  def apply (line0: Option[String], line1: String, line2: String): TLE = {

    val name = line0.map(_.substring(2).trim) // "0 <name>"
    val p = new SubstringParser(line1)
    val catNum = p.parseInt(2,7)
    val cls = line1.charAt(7)
    val launchYr = p.parseInt(9,10)
    val launchNum = p.parseInt(11,13)
    val piece = line1.substring(14,16).trim
    val epochYear = p.parseInt(18,19)
    val epochDOY = p.parseDouble(20,31)
    val dtMmo2 = p.parseDouble(33,42)
    val dt2Mmo6 = p.parseDouble(44,51)
    val bstarDrag = p.parseDoubleFraction(53,60)
    val setNum = p.parseInt(64,67)

    p.initialize(line2.toCharArray)
    val incl = p.parseDouble(8,16)
    val raan = p.parseDouble(17,25)
    val eccn = p.parseDoubleFraction(26,33)
    val argPer = p.parseDouble(34,42)
    val meanAn = p.parseDouble(43,51)
    val meanMo = p.parseDouble(52,63)
    val orbitNum = p.parseInt(63,68)

    new TLE(
      catNum, cls, launchYr, launchNum, piece, epochYear, epochDOY, dtMmo2, dt2Mmo6, bstarDrag, setNum,
      incl, raan, eccn, argPer, meanAn, meanMo, orbitNum,
      name, line1, line2
    )
  }

  def apply (line0: String, line1: String, line2: String): TLE = apply( Some(line0), line1, line2)
  def apply (line1: String, line2: String): TLE = apply( None, line1, line2)

  // value based ctor
  def apply (catNum: Int, cls: Char, launchYr: Int, launchNum: Int, piece: String, epochYear: Int, epochDOY: Double,
             dtMmo2: Double, dt2Mmo6: Double, bstarDrag: Double, setNum: Int,
             incl: Double, raan: Double, eccn: Double, argPer: Double, meanAn: Double, meanMo: Double, orbitNum: Int,
             name: Option[String] = None
            ): TLE = {
    new TLE(
      catNum, cls, launchYr, launchNum, piece, epochYear, epochDOY, dtMmo2, dt2Mmo6, bstarDrag, setNum,
      incl, raan, eccn, argPer, meanAn, meanMo, orbitNum,

      name,
      line1( catNum, cls, launchYr, launchNum, piece, epochYear, epochDOY, dtMmo2, dt2Mmo6, bstarDrag, setNum),
      line2( catNum, incl, raan, eccn, argPer, meanAn, meanMo, orbitNum)
    )
  }

  def line1 (catNum: Int, cls: Char, launchYr: Int, launchNum: Int, piece: String, epochYear: Int, epochDOY: Double,
             dtMmo2: Double, dt2Mmo6: Double, bstarDrag: Double, setNum: Int): String = {
    def fracString6 (d: Double): String = {
      import Math._
      val exp: Int = log10(abs(d)).toInt
      val n = (d * pow10(abs(exp) + 5)).toInt
      f"$n% 5d$exp%+d"
    }

    f"1 $catNum%5d$cls ${launchYr%100}%02d${launchNum%1000}%03d$piece%-3.3s ${epochYear%100}%02d$epochDOY%11.8f ${dtMmo2}%10g ${fracString6(dt2Mmo6)} ${fracString6(bstarDrag)} 0 $setNum%4d"
  }

  def line2 (catNum: Int, incl: Double, raan: Double, eccn: Double, argPer: Double, meanAn: Double, meanMo: Double, orbitNum: Int): String = {
    def eccnString(e: Double): String = {
      f"${(e * 10000000).toInt}%07d"
    }

    f"2 $catNum%5d $incl%8f $raan%8f ${eccnString(eccn)} $argPer%8f $meanAn%8f $meanMo%11f$orbitNum%5d"
  }
}

case class TLE (
  //--- line 1                                                        line  field     col
                    // line number                                       1      1   00-00
  catNum: Int,      // NORAD catalog number (e.g. 25544U for ISS)        1      2   02-06
  cls: Char,        // classification (U=unclassified)                   1      3   07-07
  launchYr: Int,    // launch year                                       1      4   09-10
  launchNum: Int,   // launch number of year                             1      5   11-13
  piece: String,    // piece of launch                                   1      6   14-16
  epochYr: Int,     // epoch year                                        1      7   18-19
  epochDOY: Double, // epoch (day-of-year incl. fraction)                1      8   20-31
  dtMmo2: Double,   // 1st deriv of Mean Motion / 2                      1      9   33–42
  dt2Mmo6: Double,  // 2nd deriv of Mean Motion / 6 (fractional part)    1     10   44-51
  bstarDrag: Double,// BSTAR drag term (fractional part)                 1     11   53-60
                    // not used                                          1     12   62-62
  setNum: Int,      // element set number (inc for new TLE for object)   1     13   64-67
                    // checksum % 10                                     1     14   68-68
  //--- line 2
                    // line number                                       2      1   00-00
                    // satNum                                            2      2   02-06
  incl: Double,     // inclination (deg)                                 2      3   08-15
  raan: Double,     // right ascension of the ascending node (degrees)   2      4   17-24
  eccn: Double,     // eccentricity (fractional part)                    2      5   26-32
  argPer: Double,   // argument of perigee (deg)                         2      6   34-41
  meanAn: Double,   // mean anomaly (deg)                                2      7   43-50
  meanMo: Double,   // mean motion (rev/day)                             2      8   52-62
  orbitNum: Int,    // revolutions at epoch                              2      9   63-67
                    // checksum % 10                                     2     10   68-68

  //--- (optional) line 0
  name: Option[String],     // satellite name (e.g. 'ZARYA' for ISS)
  line1: String,
  line2: String
) {
  //--- derived fields

  val date: DateTime = {  // of TLE
    val year = if (epochYr >= 57 && epochYr <= 99) 1900 + epochYr else 2000 + epochYr
    DateTime(year, epochDOY)
  }

  // time it takes for 1 revolution
  val period: Time = Days(1) / meanMo

  def line0: Option[String] = name  // actually a 3LE if defined

  override def toString: String = {
    if (name.isDefined) s"${name.get}\n$line1\n$line2"
    else s"$line1\n$line2"
  }

  @inline def revPerDay: Double = meanMo
}
