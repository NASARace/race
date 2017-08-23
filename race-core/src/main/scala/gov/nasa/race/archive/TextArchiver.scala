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
import org.joda.time.DateTime

import scala.annotation.tailrec


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
import TextArchiver._



/**
  * an archive reader that assumes text with begin and end marker lines
  *
  * NOTE - this class is not thread-safe, its instances should not be used concurrently. The reason is that
  * we use a per-instance buffer to avoid heap pressure due to a large number of archive entries
  */
class TextArchiveReader(val istream: InputStream) extends StreamArchiveReader {

  private val br = new BufferedReader(new InputStreamReader(istream))
  private val buf: StringBuilder = new StringBuilder(4096)

  override def hasMoreData = br.ready

  override def readNextEntry: Option[ArchiveEntry] = {
    while (true){
      br.readLine match {
        case null => return None

        case beginMarkerRE(dtg) =>
          val date = getDate(DateTime.parse(dtg))
          buf.setLength(0)
          while (true) {
            br.readLine match {
              case null | END_MARKER => someEntry(date, buf.toString)
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
  * an ArchiveWriter that stores text wrapped into begin and end marker lines
  */
class TextArchiveWriter(val ostream: OutputStream) extends ArchiveWriter {
  override def write(date: DateTime, obj: Any): Boolean = {
    ostream.write(s"\n<!-- BEGIN ARCHIVED $date -->\n".getBytes)
    ostream.write(obj.toString.getBytes)
    ostream.write(s"\n$END_MARKER\n".getBytes)
    true
  }
}