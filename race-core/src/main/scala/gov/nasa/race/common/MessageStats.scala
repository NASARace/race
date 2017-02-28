package gov.nasa.race.common

import java.io.PrintStream

import gov.nasa.race._
import gov.nasa.race.util.DateTimeUtils.durationMillisToHMMSS
import gov.nasa.race.util.FileUtils

import scala.collection.mutable.{SortedMap => MSortedMap}

/**
  * XML message statistics
  * this is the object that is used during collection, it is NOT threadsafe so don't pass
  * it around to other actors or threads
  */
class MsgStats (val msgName: String) {
  var tLast: Long = 0     // timestamp of last rate base
  var lastCount: Int = 0  // msg count of last rate base

  var byteSize: Long = 0
  var count: Int = 0
  var avgMsgPerSec: Double = 0.0
  var peakMsgPerSec: Double = 0.0

  val elems = MSortedMap.empty[String,ElemStats]

  def update (tNow: Long, tElapsed: Long, lenBytes: Int)(implicit rateBaseMillis: Int) = {
    count += 1
    byteSize += lenBytes
    avgMsgPerSec = count * 1000.0 / tElapsed

    if (tNow - tLast >= rateBaseMillis) {
      peakMsgPerSec = Math.max(peakMsgPerSec, (count - lastCount) * 1000.0 / (tNow - tLast))
      tLast = tNow
      lastCount = count
    }
  }

  def snapshot: MsgStatsSnapshot = MsgStatsSnapshot(
    msgName,byteSize,count,avgMsgPerSec,peakMsgPerSec,
    mapIteratorToArray(elems.valuesIterator,elems.size)(_.snapshot)
  )
}

/**
  * XML element statistics.
  * Those are recorded per message type
  */
class ElemStats (val pattern: String) {
  var count: Int = 0
  def snapshot: ElemStatsSnapshot = new ElemStatsSnapshot(pattern,count)
}


/**
  * this is the invariant version of MsgStats that can be processed asynchronously
  */
case class MsgStatsSnapshot (
  msgName: String,
  byteSize: Long,
  count: Int,
  avgMsgPerSec: Double,
  peakMsgPerSec: Double,
  elements: Array[ElemStatsSnapshot]
)

case class ElemStatsSnapshot (
  pattern: String,
  count: Int
)

class SubscriberMsgStats (val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                          val messages: Array[MsgStatsSnapshot]) extends Stats {
  def printToConsole = {
    printConsoleHeader

    println(s"observed channels: $channels")
    if (messages.nonEmpty) {
      var count = 0
      var avgRate = 0.0
      var peakRate = 0.0
      var byteSize = 0L

      println("  count    msg/s   peak     size    avg   msg")
      println("-------   ------ ------   ------ ------   --------------------------------------")
      for (m <- messages) {
        val memSize = FileUtils.sizeString(m.byteSize)
        val avgMemSize = FileUtils.sizeString((m.byteSize / m.count).toInt)
        println(f"${m.count}%7d   ${m.avgMsgPerSec}%6.1f ${m.peakMsgPerSec}%6.1f   $memSize%6s $avgMemSize%6s   ${m.msgName}%s")
        m.elements foreach { e =>
          println(f"                                           ${e.count}%7d : ${e.pattern}%s")
        }
        count += m.count
        avgRate += m.avgMsgPerSec
        peakRate += m.peakMsgPerSec
        byteSize += m.byteSize
      }

      if (messages.length > 1) {
        // otherwise there is no point printing summaries
        val memSize = FileUtils.sizeString(byteSize)
        val avgMemSize = if (count > 0) FileUtils.sizeString((byteSize / count).toInt) else 0

        println("-------   ------ ------   ------ ------")
        println(f"${count}%7d   ${avgRate}%6.1f ${peakRate}%6.1f   $memSize%6s $avgMemSize%6s")
      }
    }
    println
  }
}
