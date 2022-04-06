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
package gov.nasa.race.archive

import java.io._
import java.lang.StringBuilder

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigurableStreamCreator._
import gov.nasa.race.geo.{GeoPosition, XYPos}
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}


/**
 * a text (message) with an associated date
 *
 * Since the primary use of this is to store XML messages, the begin/end
 * markers for the archive stream should be XML compliant so that we can
 * run XML queries on the whole archive
 */

object TextArchiver {
  final val beginMarkerRE = """<!-- BEGIN ARCHIVED (.+) -->""".r
  final val END_MARKER = "<!-- END ARCHIVED -->"
}

/**
  * an archive reader that assumes text with begin and end marker lines
  *
  * NOTE - this class is not thread-safe, its instances should not be used concurrently. The reason is that
  * we use a per-instance buffer to avoid heap pressure due to a large number of archive entries
  */
class TextArchiveReader(val iStream: InputStream, val pathName:String="<unknown>") extends ArchiveReader {
  import TextArchiver._

  def this (conf: Config) = this(createInputStream(conf),configuredPathName(conf))

  private val br = new BufferedReader(new InputStreamReader(iStream))
  private val buf: StringBuilder = new StringBuilder(4096)

  override def hasMoreArchivedData = br.ready
  override def close(): Unit = br.close

  override def readNextEntry(): Option[ArchiveEntry] = {
    while (true){
      br.readLine match {
        case null => return None

        case beginMarkerRE(dtg) =>
          val date = getDate(DateTime.parseYMDT(dtg))
          buf.setLength(0)
          while (true) {
            br.readLine match {
              case null | END_MARKER => return archiveEntry(date, buf.toString)
              case line: String =>
                if (buf.length > 0) buf.append('\n')
                buf.append(line)
            }
          }

        case _ => // go on - extra stuff between entries
      }
    }
    None
  }
}

/**
  * an ArchiveWriter that writes to a PrintStream
  */
trait PrintStreamArchiveWriter extends ArchiveWriter {
  val oStream: OutputStream

  protected val ps = new PrintStream(oStream)

  override def close(): Unit = ps.close

}


/**
  * an ArchiveWriter that stores text wrapped into begin and end marker lines
  */
class TextArchiveWriter(val oStream: OutputStream, val pathName:String="<unknown>") extends PrintStreamArchiveWriter {

  def this (conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  override def write (date: DateTime, obj: Any): Boolean = {
    printProlog(date)
    printObject(obj)
    printEpilog

    true
  }

  //--- subclass overridable methods
  // NOTE: make sure this matches the respective TextArchiveReader regexes!

  protected def printProlog(date: DateTime): Unit = {
    ps.print("\n<!-- BEGIN ARCHIVED ")
    ps.print(date)
    ps.println(" -->")
  }

  protected def printObject (obj: Any): Unit = ps.println(obj)

  protected def printEpilog: Unit = {
    ps.println("<!-- END ARCHIVED -->")
  }
}


/**
  * a PrintStreamArchiveWriter that writes fields as CSVs
  *
  * instances have to rely on position to identify fields
  */
trait CSVArchiveWriter extends PrintStreamArchiveWriter {
  
  val fieldSep = ','
  val recSep = '\n'

  @inline def printFieldSep: Unit =  ps.print(fieldSep)

  @inline def printRecSep: Unit = ps.print(recSep)

  @inline def printString(s: String, sep: Char=fieldSep): Unit = { ps.print(s); ps.print(sep) }

  @inline def printInt(i: Int, sep: Char=fieldSep): Unit = printString(i.toString, sep)

  @inline def printDouble(d: Double, sep: Char=fieldSep): Unit = printString(d.toString, sep)

  //--- those are preserving precision

  @inline def printAngle(a: Angle, sep: Char=fieldSep): Unit = printString(a.toDegrees.toString, sep)

  @inline def printLength(l: Length, sep: Char=fieldSep): Unit = printString(l.toMeters.toString, sep)

  @inline def printSpeed(v: Speed, sep: Char=fieldSep): Unit = printString(v.toMetersPerSecond.toString, sep)


  protected def translateDate (d: DateTime): DateTime = d

  @inline def printDateTimeAsEpochMillis(d: DateTime, sep: Char=fieldSep): Unit = printString( translateDate(d).toEpochMillis.toString, sep)

  //--- rounding versions (note that precision might not suffice for some applications)

  @inline def printDegrees_0(a: Angle, sep: Char=fieldSep): Unit = ps.print(f"${a.toDegrees.round}%d$sep")
  @inline def printDegrees_5(a: Angle, sep: Char=fieldSep): Unit = ps.print(f"${a.toDegrees}%.5f$sep")

  @inline def printMeters_1(l: Length, sep: Char=fieldSep): Unit = ps.print(f"${l.toMeters}%.1f$sep")
  @inline def printMeters_3(l: Length, sep: Char=fieldSep): Unit = ps.print(f"${l.toMeters}%.3f$sep")

  @inline def printMetersPerSecond_1(v: Speed, sep: Char=fieldSep): Unit = ps.print(f"${v.toMetersPerSecond}%.1f$sep")
  @inline def printMetersPerSecond_3(v: Speed, sep: Char=fieldSep): Unit = ps.print(f"${v.toMetersPerSecond}%.3f$sep")

}