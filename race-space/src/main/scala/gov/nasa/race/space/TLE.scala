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
import gov.nasa.race.util.SubstringParser

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
  @inline def yearOf(yy: Int) = if (yy < 50) 2000 + yy else 1900 + yy

  def apply (line0: String, line1: String, line2: String): TLE = {
    val name = line0.trim

    val p = new SubstringParser(line1)

    val catNum = p.parseInt(2,7)
    val cls = line1.charAt(7)
    val launchYr = yearOf(p.parseInt(9,10))
    val launchNum = p.parseInt(11,13)
    val piece = line1.substring(14,16).trim
    val year = yearOf(p.parseInt(18,19))
    val refEpoch = p.parseDouble(20,31)
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

    new TLE(name,
            catNum,cls,launchYr,launchNum,piece,year,refEpoch,dtMmo2,dt2Mmo6,bstarDrag,setNum,
            incl,raan,eccn,argPer,meanAn,meanMo,orbitNum)
  }
}

case class TLE (    //             description                        line  field   column (0-based)
  //--- line 0
  name: String,     // satellite name (e.g. 'ZARYA' for ISS)             0
  //--- line 1
                    // line number                                       1      1   00-00
  catNum: Int,      // NORAD catalog number (e.g. 25544U for ISS)        1      2   02-06
  cls: Char,        // classification (U=unclassified)                   1      3   07-07
  launchYr: Int,    // launch year                                       1      4   09-10
  launchNum: Int,   // launch number of year                             1      5   11-13
  piece: String,    // piece of launch                                   1      6   14-16
  year: Int,        // epoch year                                        1      7   18-19
  refEpoch: Double, // epoch (day-of-year incl. fraction)                1      8   20-31
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
  orbitNum: Int     // revolutions at epoch                              2      9   63-67
                    // checksum % 10                                     2     10   68-68
) {
  //--- derived terms
  /**
  val nddot6: Double = 0
  val epoch: Double = 0
  val xndt2o: Double = 0
  val xincl: Double = 0
  val xnodeo: Double = 0
  val eo: Double = 0
  val omegao: Double = 0
  val xmo: Double = 0
  val xno: Double = 0
  val deepspace: Boolean = false
  val createddate: DateTime = DateTime.now
  **/

  def line0: String = name

  def line1: String = {
    def fracString6 (d: Double): String = {
      import Math._
      val exp: Int = log10(abs(d)).toInt
      val n = (d * pow10(abs(exp) + 5)).toInt
      f"$n% 5d$exp%+d"
    }

    def dtMmo2String (d: Double): String = {
      f"$d%10g"  // not yet - TLE omits leading "0."
    }

    val sb = new StringBuffer
    sb.append(f"1 $catNum%5d$cls ${launchYr%100}%02d${launchNum%1000}%03d$piece%-3.3s ")
    sb.append(f"${year%100}%02d$refEpoch%11.8f ${dtMmo2String(dtMmo2)} ")
    sb.append(f"${fracString6(dt2Mmo6)} ${fracString6(bstarDrag)} 0 $setNum%4d")
    sb.toString
  }

  def line2: String = {
    def eccnString (e: Double): String = {
      f"${(e * 10000000).toInt}%07d"
    }

    val sb = new StringBuffer
    sb.append(f"2 $catNum%5d $incl%8f $raan%8f ${eccnString(eccn)} $argPer%8f $meanAn%8f ")
    sb.append(f"$meanMo%11f$orbitNum%5d")
    sb.toString
  }
}
