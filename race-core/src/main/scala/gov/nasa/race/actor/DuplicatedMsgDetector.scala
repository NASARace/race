package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{MD5Checksum, Stats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, FileWriterRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.util.StringUtils

import scala.collection.JavaConverters._
import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._
import scala.util.matching.Regex

/**
  * a RaceActor that detects and reports duplicated messages
  *
  * Note this is a generic version that only compares String hashes, we don't parse XML here.
  * This means semantically identical messages with different formatting would not be detected
  */
class DuplicatedMsgDetector (val config: Config) extends SubscribingRaceActor
                     with PublishingRaceActor with ContinuousTimeRaceActor
                     with PeriodicRaceActor with FileWriterRaceActor {
  final val unclassified = "unclassified"
  case class MsgClassifier (name: String, patterns: Seq[Regex])

  val checkWindow = config.getFiniteDurationOrElse("check-window", 5.minutes).toMillis
  // override the MonitoredRaceActor defaults
  override def defaultTickInterval = 10.seconds
  override def defaultTickDelay = 10.seconds

  val checksums = MSortedMap.empty[String,Long]
  val md5 = new MD5Checksum

  val classifiers = getClassifiers
  val dupStats = MSortedMap.empty[String,DupStats]
  val title = config.getStringOrElse("title", name)
  var channels = ""

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    channels = readFromAsString
    startScheduler
  }

  override def handleMessage = {
    case BusEvent(_, msg: String, _) => checkMessage(msg)
    case RaceTick =>
      publish(snapshot)
      purgeOldChecksums
  }

  def checkMessage (msg: String) = {
    val cs = md5.getHexChecksum(msg)
    val tNow = updatedSimTimeMillis

    checksums.get(cs) match {
      case Some(tLast) =>
        ifSome(classify(msg)) { c =>
          val ds = dupStats.getOrElseUpdate(c.name, new DupStats(c.name))
          ds.count += 1
          ds.dtMillis += tNow - tLast
          logDuplicate(msg, tNow, tLast)
        }
        checksums += cs -> tNow // update time

      case None => checksums += cs -> tNow
    }
  }

  def snapshot = new SubscriberDupStats(title, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart,
                                        channels, mapIteratorToArray(dupStats.valuesIterator,dupStats.size)(_.snapshot))

  def purgeOldChecksums = {
    val tNow = updatedSimTimeMillis
    checksums foreach { e =>
      if (tNow - e._2 > checkWindow) {
        checksums -= e._1
      }
    }
  }

  def getClassifiers: Seq[MsgClassifier] = {
    val catchAll = List(MsgClassifier("unclassified",Seq(".*".r)))

    config.getOptionalConfigList("classifiers").reverse.foldLeft(catchAll) { (list, conf) =>
      val name = conf.getString("name")
      val patterns = conf.getStringList("patterns").asScala.map(new Regex(_))
      MsgClassifier(name, patterns) :: list
    }
  }

  def classify (msg: String): Option[MsgClassifier] = {
    classifiers.foreach(c => if (StringUtils.matchesAll(msg,c.patterns)) return Some(c) )
    None
  }

  def logDuplicate (msg: String, tNow: Long, tLast: Long) = {
    write(s"\n<!-- BEGIN DUPLICATED ${dateMillisToTTime(tLast)} ${dateMillisToTTime(tNow)} -->\n")
    write(msg)
    write("\n<!-- END DUPLICATED -->\n")
  }
}

class DupStats (val classifier: String) {
  var count = 0
  var dtMillis = 0L
  def snapshot: DupStatsSnapshot = DupStatsSnapshot(classifier,count,dtMillis)
}
case class DupStatsSnapshot (
  classifier: String,
  count: Int,
  dtMillis: Long
)


class SubscriberDupStats (val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                          val messages: Array[DupStatsSnapshot]) extends Stats {
  def printToConsole = {
    printConsoleHeader
    println(s"observed channels: $channels")

    if (messages.nonEmpty) {
      println("  count     avg sec   classifier")
      println("-------   ---------   -------------------------------------------")
      for (m <- messages) {
        val avgDtSecs = m.dtMillis.toDouble / (m.count * 1000.0)
        println(f"${m.count}%7d   $avgDtSecs%9.3f   ${m.classifier}")
      }
    }
    println
  }
}
