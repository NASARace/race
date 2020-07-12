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
package gov.nasa.race.common

import java.io.{FileOutputStream, PrintStream}

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.duration._
import scala.io.Source

/**
  * unit test for SmoothingExtrapolator (irregular time series extrapolation)
  */
class SmoothingExtrapolatorSpec extends AnyFlatSpec with RaceSpec {

  mkTestOutputDir

  behavior of "smoothing extrapolator"
  "extrapolator" should "reproduce known values" in {

    val ex = new SmoothingExtrapolator(3.milliseconds)

    val data = Array[(Long,Double)]( (0,6), (1,7), (5,7), (7,8), (10,8), (12,9), (15,7), (20,6) )

    var j=0 // next data index
    for (i <- 0 to 25) {
      val y = ex.extrapolate(i)
      println(f"$i%2d : $y%.1f")

      if (j < data.length && data(j)._1 == i) {
        val d = data(j)
        ex.addObservation(d._2,d._1)
        println(s"    add $d")
        j += 1
      }
    }
  }

  "extrapolator" should "produce strictly decreasing values with limited delta" in {
    val ex = new SmoothingExtrapolator(500.milliseconds, 0.3,0.1)
    val dmMax = 0.06

    val data = Source.fromFile(baseResourceFile("smoothing.dat"))
    val dataLine = """ *([\d.]+) *(\d+)""".r
    val ignoredLine = """^ *(#.*)?$""".r
    var eLast = 0.0
    var mLast = 0.0
    var tLast: Long = 0

    val gpFile = testOutputFile("extrapolate.dat")
    val ps = new PrintStream(new FileOutputStream(gpFile))

    println("\n------------- checking strictly monotonic decreasing estimates")

    for ((line,i) <- data.getLines().zipWithIndex){
      line match {
        case ignoredLine(cmt) => // ignore

        case dataLine(st,sa) =>
          val t = (st.toDouble * 1000).toLong  // observations stored in seconds with fractions
          val y = sa.toDouble

          val e = ex.extrapolate(t)
          val m = if (i < 3) 0.0 else (e - eLast) / (t - tLast)

          if (i > 2) assert( m < 0 && Math.abs(m) < dmMax)

          ex.addObservation(y,t)

          val line = f"${t/1000.0}%6.3f  $y%5.0f  ${Math.round(e)}%5d  $m%.3f\n"
          print(line)
          ps.print(line)

          eLast = e
          mLast = m
          tLast = t

        case other => fail(s"invalid input data line: $other")
      }
    }
    ps.close
    println(s" visualize with gnuplot: plot '$gpFile' using 1:2 with line, '$gpFile' using 1:3 with lines")
  }
}
