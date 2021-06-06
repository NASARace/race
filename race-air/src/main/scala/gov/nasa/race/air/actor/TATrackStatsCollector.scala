/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
package gov.nasa.race.air.actor

import java.io.PrintWriter

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.{ChannelOptionPublisher, StatsCollectorActor}
import gov.nasa.race.air.TaisTrack
import gov.nasa.race.common.TSStatsData.{Ambiguous, Duplicate, Extension, Sameness}
import gov.nasa.race.common._
import gov.nasa.race.core.ClockAdjuster
import gov.nasa.race.core.{BusEvent, RaceTick}
import gov.nasa.race.http.{HtmlArtifacts, HtmlStats, HtmlStatsFormatter}

import scala.collection.mutable.{HashMap => MHashMap}
import scalatags.Text.all._

/**
  * actor that collects statistics for TATrack objects
  * We keep stats per tracon, hence this is not directly a TSStatsCollectorActor
  */
class TATrackStatsCollector (val config: Config) extends StatsCollectorActor with ClockAdjuster with ChannelOptionPublisher {

  class TACollector (val config: Config, val src: String)
         extends ConfiguredTSStatsCollector[String,TaisTrack,TATrackEntryData,TATrackStatsData] {
    val statsData = new TATrackStatsData(src)
    statsData.buckets = createBuckets

    if (hasChannelOptions){
      statsData.duplicateAction = Some(logDuplicate)
      statsData.blackoutAction = Some(logBlackout)
      statsData.ambiguousAction = Some(logAmbiguous)
      statsData.outOfOrderAction = Some(logOutOfOrder)
    }

    def createTSEntryData (t: Long, track: TaisTrack) = new TATrackEntryData(t,track)
    def currentSimTimeMillisSinceStart = TATrackStatsCollector.this.currentSimTimeMillisSinceStart
    def currentSimTimeMillis = TATrackStatsCollector.this.updatedSimTimeMillis

    override def dataSnapshot: TATrackStatsData = {
      processEntryData
      super.dataSnapshot
    }
  }

  val tracons = MHashMap.empty[String, TACollector]

  override def onRaceTick(): Unit = {
    tracons.foreach { e => e._2.checkDropped }
    publish(snapshot)
  }

  override def handleMessage = {
    case BusEvent(_, track: TaisTrack, _) =>
      if (track.date.isDefined) {
        checkInitialClockReset(track.date)
        val tracon = tracons.getOrElseUpdate(track.src, new TACollector(config, track.src))
        if (track.isDropped) {
          tracon.removeActive(track.id, track)
        } else {
          tracon.updateActive(track.id, track)
        }
      }
  }

  def snapshot: Stats = {
    val traconStats = tracons.toSeq.sortBy(_._1).map( e=> e._2.dataSnapshot)
    new TATrackStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, traconStats)
  }

  //--- problem logging (only called if there is a log channel)

  def logUpdate (channel: String, t1: TaisTrack, t2: TaisTrack, details: Option[String]=None): Unit = {
    val sb = new StringBuilder

    def appendTrack (prefix: String, t: TaisTrack) = {
      sb.append(prefix)
      sb.append(" [")
      sb.append(objRef(t))
      sb.append("]: ")
      sb.append(t)
      sb.append('\n')
    }
    def appendSrc (prefix: String, s: Src[String]) = {
      sb.append(prefix)
      sb.append(" [")
      sb.append(objRef(s.src))
      sb.append("]: ")
      sb.append(s.src)
      sb.append('\n')
    }

    sb.append("============= ");
    sb.append(channel)
    details.foreach { s=>
      sb.append(" ")
      sb.append(s)
    }
    sb.append('\n')
    appendTrack("current track:  ", t1)
    appendTrack("previous track: ", t2)

    ifSome(t1.getFirstAmendmentOfType[Src[String]]){ appendSrc("-----------\ncurrent src:  ",_)}
    ifSome(t2.getFirstAmendmentOfType[Src[String]]){ appendSrc("-----------\nprevious src: ",_)}

    publishToChannelOption(channel,sb.toString)
  }

  def logDuplicate (t1: TaisTrack, t2: TaisTrack): Unit = logUpdate("duplicatedTrack",t1,t2)
  def logBlackout (t1: TaisTrack, t2: TaisTrack): Unit = logUpdate("blackoutTrack", t1,t2)
  def logAmbiguous (t1: TaisTrack, t2: TaisTrack, reason: Option[String]) = logUpdate("ambiguousTrack",t1,t2,reason)
  def logOutOfOrder (t1: TaisTrack, t2: TaisTrack, amount: Option[String]): Unit = logUpdate("outOfOrderTrack", t1,t2, amount)
}

class TATrackEntryData (tLast: Long, track: TaisTrack) extends TSEntryData[TaisTrack](tLast,track) {
  var nFlightPlan = if (track.hasFlightPlan) 1 else 0 // messages with flight plan

  override def update (obj: TaisTrack, isSettled: Boolean) = {
    super.update(obj,isSettled)
    if (obj.hasFlightPlan) nFlightPlan += 1
  }
  // add consistency status
}

class TATrackStatsData  (val src: String) extends TSStatsData[TaisTrack,TATrackEntryData] {
  var nNoTime = 0 // number of track positions without time stamps
  var nFlightPlans = 0 // number of active entries with flight plan
  val stddsRevs = new Array[Int](4)

  def updateTATrackStats (obj: TaisTrack) = {
    obj.getFirstAmendmentOfType[Rev] match {
      case Some(rev) =>
        rev.level1 match {
          case 2 => stddsRevs(2) += 1
          case 3 => stddsRevs(3) += 1
          case other => // ignore, that would cause a schema violation
        }
      case None => // ignore
    }
    if (obj.date.isUndefined) nNoTime += 1
  }

  override def update (obj: TaisTrack, e: TATrackEntryData, isSettled: Boolean): Unit = {
    super.update(obj,e,isSettled) // standard TSStatsData collection
    updateTATrackStats(obj)
  }

  override def add (obj: TaisTrack, isStale: Boolean, isSettled: Boolean) = {
    super.add(obj,isStale,isSettled)
    updateTATrackStats(obj)
  }

  override def rateSameness (t1: TaisTrack, t2: TaisTrack): Sameness = {
    // we don't need to compare src and trackNum since those are used to look up the respective entries
    if (t1.position != t2.position) Ambiguous(Some("position"))
    else if (t1.speed != t2.speed) Ambiguous(Some("speed"))
    else if (t1.heading != t2.heading) Ambiguous(Some("heading"))
    else if (t1.vr != t2.vr) Ambiguous(Some("vr"))
    else if (t1.beaconCode != t2.beaconCode) Ambiguous(Some("beaconCode"))
    else if (t1.hasFlightPlan != t2.hasFlightPlan) Extension  // we treat flight plans as accumulating
    else Duplicate
  }


  override def resetEntryData = {
    nFlightPlans = 0
  }

  // called on all active entries before the Stats snapshot is created
  override def processEntryData (e: TATrackEntryData) = {
    if (e.nFlightPlan > 0) nFlightPlans += 1
  }

  def stddsRev = {
    if (stddsRevs(2) > 0){
      if (stddsRevs(3) > 0) "2/3" else "2"
    } else {
      if (stddsRevs(3) > 0) "3" else "?"
    }
  }

  override def toXML = {
    s"""    <center src="$src">
       $xmlBasicTSStatsData
       $xmlBasicTSStatsFindings
       $xmlSamples
    </center>"""
  }
}

class TATrackStats(val topic: String, val source: String, val takeMillis: Long, val elapsedMillis: Long,
                   val traconStats: Seq[TATrackStatsData]) extends PrintStats {

  val nTracons = traconStats.size
  var nActive = 0
  var nCompleted = 0
  var nFlightPlans = 0
  var nStale = 0
  var nDropped = 0
  var nBlackout = 0
  var nOutOfOrder = 0
  var nDuplicates = 0
  var nAmbiguous = 0
  val stddsRevs = new Array[Int](4)
  var nNoTime = 0

  traconStats.foreach { ts =>
    nActive += ts.nActive
    nFlightPlans += ts.nFlightPlans
    nCompleted += ts.completed

    nStale += ts.stale
    nDropped += ts.dropped
    nBlackout += ts.blackout
    nOutOfOrder += ts.outOfOrder
    nDuplicates += ts.duplicate
    nAmbiguous += ts.ambiguous
    if (ts.stddsRevs(2) > 0) stddsRevs(2) += 1
    if (ts.stddsRevs(3) > 0) stddsRevs(3) += 1
    nNoTime += ts.nNoTime
  }

  // the default is to print only stats for all centers
  override def printWith (pw: PrintWriter): Unit = {
    pw.println("tracons  v2  v3    tracks   fplan   cmplt   dropped    blck   order     dup   ambig   no-time")
    pw.println("------- --- ---   ------- ------- -------   ------- ------- ------- ------- -------   -------")
    pw.print(f"$nTracons%7d ${stddsRevs(2)}%3d ${stddsRevs(3)}%3d   $nActive%7d $nFlightPlans%7d $nCompleted%7d")
    pw.print(f"   $nDropped%7d $nBlackout%7d $nOutOfOrder%7d $nDuplicates%7d $nAmbiguous%7d   $nNoTime%7d")
  }

  override def xmlData: String = {
    s"""    <taTrackStats>
      ${traconStats.map( _.toXML).mkString("\n      ")}
    </taTrackStats>"""
  }
}

class TATrackStatsFormatter (conf: Config) extends PrintStatsFormatter {

  def printWith (pw: PrintWriter, stats: Stats) = {
    stats match {
      case s: TATrackStats => printTATrackStats(pw,s)
      case other => false
    }
  }

  def printTATrackStats (pw: PrintWriter, s: TATrackStats): Boolean = {
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}
    import s._

    //--- totals
    s.printWith(pw)
    pw.print("\n\n")

    //--- per tracon data
    pw.println(" tracon     rev    tracks   fplan   cmplt   dropped    blck   order     dup   ambig   no-time         n dtMin dtMax dtAvg")
    pw.println("------- -------   ------- ------- -------   ------- ------- ------- ------- -------   -------   ------- ----- ----- -----")
    traconStats.foreach { ts =>
      pw.print(f"${ts.src}%7s ${ts.stddsRev}%7s   ${ts.nActive}%7d ${ts.nFlightPlans}%7d ${ts.completed}%7d   ")
      pw.print(f"${ts.dropped}%7d ${ts.blackout}%7d ${ts.outOfOrder}%7d ${ts.duplicate}%7d ${ts.ambiguous}%7d   ${ts.nNoTime}%7d")
      ifSome(ts.buckets) { bc =>
        pw.print(f"   ${bc.numberOfSamples}%7d ${dur(bc.min)}%5s ${dur(bc.max)}%5s ${dur(bc.mean)}%5s")
      }
      pw.println
    }

    true
  }
}

class HtmlTATrackStatsFormatter (config: Config) extends HtmlStatsFormatter {

  override def toHtml(stats: Stats): Option[HtmlArtifacts] = {
    stats match {
      case stats: TATrackStats => Some(HtmlArtifacts(statsToHtml(stats), HtmlStats.noResources))
      case _ => None
    }
  }

  def statsToHtml (s: TATrackStats) = {
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}
    import s._

    div(
      HtmlStats.htmlTopicHeader(topic,source,elapsedMillis),
      table(cls:="noBorder")(
        tr(cls:="border")(
          th("src"),th("v2"),th("v3"),th("tracks"),th("fPlan"),th("min"),th("max"),th("cmplt"),th(""),
          th("n"),th("Δt min"),th("Δt max"),th("Δt avg"),th(""),
          th("stale"),th("drop"),th("blck"),th("order"),th("dup"),th("amb"),th("no-t")
        ),

        //--- sum row
        tr(cls:="border")(
          td(nTracons),td(stddsRevs(2)),td(stddsRevs(3)),td(nActive),td(nFlightPlans),td(""),td(""),td(""),td(""),
          td(""),td(""),td(""),td(""),td(""),
          td(nStale),td(nDropped),td(nBlackout),td(nOutOfOrder),td(nDuplicates),td(nAmbiguous),td(nNoTime)
        ),

        //--- tracon rows
        for (t <- traconStats) yield tr(cls:="value top")(
          td(t.src),td(t.stddsRevs(2)),td(t.stddsRevs(3)),td(t.nActive),td(t.nFlightPlans),td(t.minActive),td(t.maxActive),td(t.completed),td(""),
          t.buckets match {
            case Some(bc) if bc.numberOfSamples > 0 => Seq( td(bc.numberOfSamples),td(dur(bc.min)),td(dur(bc.max)),td(dur(bc.mean)))
            case _ => Seq( td("-"),td("-"),td("-"),td("-"))
          },td(""),
          td(t.stale),td(t.dropped),td(t.blackout),td(t.outOfOrder),td(t.duplicate),td(t.ambiguous),td(t.nNoTime)
        )
      )
    )
  }
}