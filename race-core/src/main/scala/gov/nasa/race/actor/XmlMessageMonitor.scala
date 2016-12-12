package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.DateTimeUtils._
import gov.nasa.race.core.Messages.RaceCheck
import gov.nasa.race.core.{BusEvent, ContinuousTimeRaceActor, MonitoredRaceActor, RaceContext, SubscribingRaceActor}
import gov.nasa.race.util.{ConsoleIO, FileUtils, XmlPullParser}

import scala.collection.mutable
import scala.concurrent.duration._

trait MsgStatsReporter {
  def report (channels: String, tElapsedMillis: Long, msgStats: mutable.SortedMap[String,MsgStats])
}

class ElemStats (val pattern: String) {
  var nTotal: Int = 0
}

class MsgStats (val name: String, val rateBaseMillis: Long) {
  var tLast: Long = 0     // timestamp of last rate base
  var lastTotal: Int = 0  // msg count of last rate base

  var nSizeBytes: Long = 0
  var nTotalMsg: Int = 0
  var avgMsgPerSec: Double = 0.0
  var peakMsgPerSec: Double = 0.0

  val elems = mutable.SortedMap.empty[String,ElemStats]

  def update (tNow: Long, tElapsed: Long, lenBytes: Int) = {
    nTotalMsg += 1
    nSizeBytes += lenBytes
    avgMsgPerSec = nTotalMsg * 1000.0 / tElapsed

    if (tNow - tLast >= rateBaseMillis) {
      peakMsgPerSec = Math.max(peakMsgPerSec, (nTotalMsg - lastTotal) * 1000.0 / (tNow - tLast))
      tLast = tNow
      lastTotal = nTotalMsg
    }
  }
}

/**
  * a SubscribingRaceActor that reports number, rate and type of XML messages received from its
  * input channels
  *
  * We want to be able to report sub-elements up to a configured pattern depth
  */
class XmlMessageMonitor (val config: Config) extends SubscribingRaceActor
         with ContinuousTimeRaceActor with MonitoredRaceActor {

  final val defaultRateBaseMillis = 2000 // get peak rate over 2 sec
  override def defaultCheckInterval = 10.seconds
  override def defaultCheckDelay = 20.seconds

  val patternSpecs = config.getStringArray("patterns") // optional, if none we only parse top level
  val ignorePatterns = config.getStringListOrElse("ignore",Seq.empty[String])
  val rateBaseMillis = config.getIntOrElse("rate-base", defaultRateBaseMillis)

  val msgStats = mutable.SortedMap.empty[String,MsgStats]
  val reporter = createReporter(config.getOptionalConfig("reporter"))
  var channels = "" // might change

  class Parser extends XmlPullParser {
    val pathQueries = patternSpecs map( ps => compileGlobPathQuery(ps.split("/")))

    def parse(input: String) = {
      initialize(input.toCharArray)

      var isFirstElement = true
      var msgStat: MsgStats = null
      var done = false

      while (!done && parseNextElement) {
        if (isStartElement) {
          if (isFirstElement){
            isFirstElement = false
            msgStat = msgStats.getOrElseUpdate(tag,new MsgStats(tag,rateBaseMillis))
            msgStat.update(updatedSimTimeMillis,elapsedSimTimeMillisSinceStart,input.length)
            if (pathQueries.isEmpty) done = true // no need to parse all elements

          } else { // we have pathQueries. Avoid allocation
            var idx = 0
            pathQueries foreach { pqId =>
              if (isMatchingPath(pqId)) {
                val pattern = patternSpecs(idx)
                val elemStat = msgStat.elems.getOrElseUpdate(pattern,new ElemStats(pattern))
                elemStat.nTotal += 1
              }
              idx += 1
            }
          }
        }
      }
    }
  }

  val parser = new Parser

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    channels = readFromAsString
    startMonitoring
  }

  override def handleMessage = {
    case BusEvent(_,msg: String,_) => parser.parse(msg)
    case RaceCheck => reporter.report(channels,updatedElapsedSimTimeMillisSinceStart,msgStats)
  }

  def createReporter (reporterConf: Option[Config]): MsgStatsReporter = {
    reporterConf match {
      case Some(conf) =>
        val reporter = newInstance[MsgStatsReporter](conf.getString("class"), Array(classOf[Config]), Array(config)).get
        info(s"instantiated reporter ${reporter.getClass.getName}")
        reporter
      case None => new ConsoleReporter
    }
  }
}

class ConsoleReporter extends MsgStatsReporter {
  def report (channels: String, tElapsedMillis: Long, msgStats: mutable.SortedMap[String,MsgStats]) = {
    ConsoleIO.clearScreen
    println(s"XML statistics on channels: $channels")
    println(s"         observed duration: ${durationMillisToHMMSS(tElapsedMillis)}")
    println("  total    msg/s   peak     size    avg   msg")
    println("-------   ------ ------   ------ ------   ----------------------------------------------------------")
    for (m <- msgStats.valuesIterator) {
      val memSize = FileUtils.sizeString(m.nSizeBytes)
      val avgMemSize = FileUtils.sizeString((m.nSizeBytes / m.nTotalMsg).toInt)
      println(f"${m.nTotalMsg}%7d   ${m.avgMsgPerSec}%6.1f ${m.peakMsgPerSec}%6.1f   $memSize%6s $avgMemSize%6s   ${m.name}%s")
      for (e <- m.elems.valuesIterator) {
        println(f"                                           ${e.nTotal}%7d : ${e.pattern}%s")
      }
    }
  }
}
