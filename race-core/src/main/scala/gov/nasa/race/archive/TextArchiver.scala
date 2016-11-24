package gov.nasa.race.archive

import java.io._

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
  * Created by pcmehlitz on 10/15/16.
  */
class TextArchiveReader(val istream: InputStream) extends ArchiveReader {

  val br = new BufferedReader(new InputStreamReader(istream))

  @tailrec private def skipToBegin: Option[DateTime] = {
    br.readLine match {
      case null => None
      case beginMarkerRE(dtg) => Some(getDate(DateTime.parse(dtg)))
      case other => skipToBegin
    }
  }

  @tailrec private def readToEnd(sb: StringBuilder): Option[String] = {
    br.readLine match {
      case null | END_MARKER =>
        if (sb.isEmpty) None else Some(sb.toString)
      case line =>
        if (sb.size > 0) sb.append('\n')
        sb.append(line)
        readToEnd(sb)
    }
  }

  override def hasMoreData = br.ready

  override def read: Option[ArchiveEntry] = {
    try {
      skipToBegin match {
        case Some(date) =>
          readToEnd(new StringBuilder) match {
            case Some(text) => Some(ArchiveEntry(date, text))
            case None => None
          }
        case None => None
      }
    } catch {
      case t: IOException => None
    }
  }
}

/**
  * Created by pcmehlitz on 10/15/16.
  */
class TextArchiveWriter(val ostream: OutputStream) extends ArchiveWriter {
  override def write(date: DateTime, obj: Any): Boolean = {
    ostream.write(s"\n<!-- BEGIN ARCHIVED $date -->\n".getBytes)
    ostream.write(obj.toString.getBytes)
    ostream.write(s"\n$END_MARKER\n".getBytes)
    true
  }
}