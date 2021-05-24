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
package gov.nasa.race.common

import java.io.PrintWriter

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.{Dated, _}

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration._

object TSStatsData {

  /** algebraic type to assess same-timestamp updates */
  sealed abstract class Sameness
  case object Extension extends Sameness // something got added
  case object Duplicate extends Sameness  // semantics of update objects are the same (not necessarily all their fields)
  case class Ambiguous (reason: Option[String]=None) extends Sameness // conflicting semantics (e.g. different positions)

  /** algebraic type to assess increasing timestamp updates */
  sealed abstract class Change
  case object Plausible extends Change
  case class Implausible (reason: Option[String]=None) extends Change
}

/**
  * statistics data for a generic, irregular time series, i.e. sequences of update events for dated objects
  * such as flights, radar tracks etc.
  *
  * This is the data that needs to be cloned for a snapshot, it should not contain any fields which are just
  * used to collect the data. For that reason, it is not a good idea to mix this into the TSStatsCollector, which
  * usually is an actor.
  *
  * TimeSeriesStats are created by TimeSeriesStatsCollectors, which are in turn  parameterized with the concrete
  * TimeSeriesStats type, which is also where we get the TSEntry type and objects from.
  *
  * The basic implementation only keeps track of general active/min/max/total update interval counts, and of some
  * generic error conditions such as out-of-order updates (update message with older timestamp arriving later)
  */
trait TSStatsData[O <: Dated,E <: TSEntryData[O]] extends Cloneable with XmlSource {
  import TSStatsData._

  type OAction = (O)=>Unit    // function to process single update object finding
  type OOAction = (O,O)=>Unit // function to process current/previous update object finding
  type OORAction = (O,O,Option[String])=>Unit // function to process current/previous update object finding with optional reason

  //--- global stats over all entries
  var nUpdates = 0 // number of update calls
  var dtMin = 0 // smallest update interval > 0
  var dtMax = 0 // largest update interval
  var nActive = 0 // current number of actives
  var minActive = 0 // low water mark for activeEntries (after settle-in period)
  var maxActive = 0 // high water mark for activeEntries
  var completed = 0 // entries that were explicitly removed (to see if there is re-organization in case of dropped entries)

  //--- basic findings statistics
  var stale = 0  // entries that are outdated when they are first reported (dead on arrival)
  var dropped = 0 // entries that are dropped because they didn't get updated within a certain time
  var blackout = 0 // entries that are re-entered before blackout time
  var outOfOrder = 0 // updates that have a time stamp that is older than the previous update
  var duplicate = 0 // updates that are semantically the same as the previous update
  var ambiguous = 0 // updates that have the same time stamp but are otherwise not the same as the previous update
  var implausible = 0 // updates that don't seem plausible regardless of positive time difference

  //--- optional actions to further analyze or archive problems, to be set by owner
  var staleAction: Option[OAction] = None
  var dropAction: Option[OAction] = None
  var blackoutAction: Option[OOAction] = None
  var outOfOrderAction: Option[OORAction] = None
  var duplicateAction: Option[OOAction] = None
  var ambiguousAction: Option[OORAction] = None
  var implausibleAction: Option[OORAction] = None

  //--- can be set by client/owner to get basic update distriution data
  var buckets: Option[BucketCounter] = None  // has to be var so that we can clone


  //--- override for specialized actions (make sure not to introduce fields that should not be part of the snapshot)

  // ideally we would like to return our concrete type, but that would require another rather non-intuitive
  // (recursive) type parameter plus a self type, and clones are probably just obtained through TSStatsCollector APIs
  override def clone: TSStatsData[O,E] = {
    // we need to deep-copy in case we have a bucket counter
    val clon = super.clone.asInstanceOf[TSStatsData[O,E]]
    ifSome(buckets) { bc=> clon.buckets = Some(bc.clone) }
    clon
  }

  //--- standard update categorization support

  // override this if fields/values for ambiguity are a subset of duplication
  def rateSameness (current: O, last: O): Sameness = {
    if (current == last) Duplicate else Ambiguous()
  }

  // override this if we evaluate change plausibility
  def rateChange (current: O, last: O): Change = Plausible

  //--- update interface

  // new entry (might be stale already)
  def add (obj: O, isStale: Boolean, isSettled: Boolean) = {
    if (!isStale) {
      nActive += 1
      if (nActive > maxActive) maxActive = nActive
    } else {
      stale += 1
      ifSome(staleAction){ _(obj) }
    }
  }

  //--- updates

  def updatePositiveTimeDelta (dt: Int, obj: O, e: E, isSettled: Boolean): Unit = {
    rateChange(obj,e.lastObj) match {
      case Plausible =>
        if (dt > dtMax) dtMax = dt
        if (isSettled) {
          if (dt < dtMin || dtMin == 0) dtMin = dt
          buckets.foreach(_.addSample(dt))
        }
      case Implausible(reason) =>
        implausible += 1
        ifSome(implausibleAction){ _(obj,e.lastObj,reason) }
    }
  }

  def updateSameTime (obj: O, e: E): Unit = {
    rateSameness(obj,e.lastObj) match {
      case Duplicate =>
        duplicate += 1
        ifSome(duplicateAction){ _(obj,e.lastObj) }

      case Ambiguous(reason) =>
        ambiguous += 1
        ifSome(ambiguousAction){ _(obj,e.lastObj,reason) }

      case Extension =>
        // let pass
    }
  }

  def updateNegativeTimeDelta (dtMillis: Int, obj: O, e: E): Unit = {
    outOfOrder += 1
    ifSome(outOfOrderAction){ _(obj,e.lastObj,Some(s"$dtMillis msec")) }
  }

  // on-the-fly update of entry
  // note that the entryStats are not updated yet so that we can assess the changes
  def update (obj: O, e: E, isSettled: Boolean): Unit = {
    val dt = (obj.date.toEpochMillis - e.tLast).toInt
    if (dt > 0){
      updatePositiveTimeDelta(dt,obj,e,isSettled) // plausible/implausible
    } else if (dt == 0) {
      updateSameTime(obj,e) // duplicate/ambiguous
    } else {
      updateNegativeTimeDelta(dt,obj,e) // outOfOrder
    }
  }

  def blackedOut (obj: O, e: E, isSettled: Boolean): Unit = {
    blackout += 1
    ifSome(blackoutAction){ _(obj,e.lastObj) }
  }

  // explicitly terminated (obj state might still have new info)
  def remove (e: E, isSettled: Boolean): Unit = {
    completed += 1
    nActive -= 1
    updateMinActive(isSettled)
  }

  // removed during check for entries that did not receive updates in time
  def drop (e: E, isSettled: Boolean): Unit = {
    dropped += 1
    nActive -= 1
    updateMinActive(isSettled)
    ifSome(dropAction){ _(e.lastObj) }
  }

  def updateMinActive(isSettled: Boolean) = if (isSettled && (minActive == 0 || nActive < minActive)) minActive = nActive

  // can be overridden to collect entry specific data upon snapshot
  def resetEntryData: Unit = {}
  def processEntryData (e: E): Unit = {} // default is to do nothing


  //--- XML representation

  def xmlBasicTSStatsData = {
    s"""    <updates>$nUpdates</updates>
      <active>$nActive</active>
      <minActive>$minActive</minActive>
      <maxActive>$maxActive</maxActive>
      <completed>$completed</completed>
      <dtMin>$dtMin</dtMin>
      <dtMax>$dtMax</dtMax>"""
  }

  def xmlBasicTSStatsFindings: String = {
    s"""     <stale>$stale</stale>
      <dropped>$dropped</dropped>
      <blackout>$blackout</blackout>
      <outOfOrder>$outOfOrder</outOfOrder>
      <duplicates>$duplicate</duplicates>
      <ambiguous>$ambiguous</ambiguous>"""
  }

  def xmlSamples = buckets match {
    case Some(bc) => bc.toXML
    case None => ""
  }


  // override if there are additional fields
  def toXML = {
    s"""    <series>
       $xmlBasicTSStatsData
       $xmlBasicTSStatsFindings
       $xmlSamples
    </series>"""
  }
}

class TimeSeriesStats[O <: Dated,E <: TSEntryData[O]](val topic: String,
                                                      val source: String,
                                                      val takeMillis: Long,
                                                      val elapsedMillis: Long,
                                                      val data: TSStatsData[O,E]
                                                     ) extends PrintStats {
  def printWith (pw:PrintWriter) = {
    import data._
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}

    pw.println("active    min    max cmplt   stale  drop  blck order   dup ambig         n dtMin dtMax dtAvg")
    pw.println("------ ------ ------ -----   ----- ----- ----- ----- ----- -----   ------- ----- ----- -----")
    pw.print(f"$nActive%6d $minActive%6d $maxActive%6d $completed%5d   $stale%5d $dropped%5d $blackout%5d $outOfOrder%5d $duplicate%5d $ambiguous%5d")

    buckets match {
      case Some(bc)  =>
        pw.println(f"   ${bc.numberOfSamples}%7d ${dur(bc.min)}%5s ${dur(bc.max)}%5s ${dur(bc.mean)}%5s")

        if (bc.nBuckets > 1 && bc.numberOfSamples > 0) {
          bc.processBuckets((i, c) => {
            if (i % 6 == 0) pw.println // 6 buckets per line
            pw.print(f"${Math.round(i * bc.bucketSize / 1000)}%3ds: $c%6d | ")
          })
        }
      case _ => // no buckets to report
    }
    pw.println
  }

  override def xmlData: String = data.toXML
}

trait BasicTimeSeriesStats[O <: Dated] extends TimeSeriesStats[O,TSEntryData[O]]

/**
  * the per-entry time series update data for flight,track etc.
  * Note these are not per-se part of the Stats snapshots, we only store data on-the-fly here which is used to compute
  * persistent statistics. It is therefore less likely but still possible to provide a customized entry stats type
  */
class TSEntryData[O <: Dated](var tLast: Long, var lastObj: O) extends Cloneable {
  var removed: Boolean = false // set to true after receiving a completed event

  def update (obj: O, isSettled: Boolean) = {
    val t = obj.date.toEpochMillis
    val dt = (t - tLast).toInt

    if (dt > 0) {
      tLast = t
      lastObj = obj
    }
  }
}

/**
  * a TimeSeriesEntryStats implementation that keeps basic stats values for single objects
  */
class BasicTimeSeriesEntryStats[O <: Dated] (t: Long, o: O) extends TSEntryData[O](t,o) {
  var count: Int = 0
  var dtSum: Int = 0  // to compute average update interval
  var dtLast: Int = 0
  var dtMin: Int = 0
  var dtMax: Int = 0

  /**
    * can be overridden to update additional stats, but make sure to call super.update
    */
  override def update (obj: O, isSettled: Boolean): Unit = {
    val t = obj.date.toEpochMillis
    val dt = (t - tLast).toInt

    if (dt > 0) {
      tLast = t
      lastObj = obj

      count += 1
      dtLast = dt
      dtSum += dt
      if (dt > dtMax) dtMax = dt
      if (isSettled && (dt < dtMin || dtMin == 0)) dtMin = dt
    }
  }
}


/**
  * a trait that keeps track of update statistics for Dated objects
  *
  * This is mostly meant to be a mix-in for actors that publish Stats. For configurable entities, it is
  * usually mixed in through the convenience trait ConfiguredTSStatsCollector
  *
  * concrete types have to be parameterized with the respective dated update object type, a key type that
  * identifies object instances, the entry type we use to store the last update, and the stats data type
  * which is then used to create the Stats object from
  */
trait TSStatsCollector[K,O <: Dated,E <: TSEntryData[O],S <: TSStatsData[O,E]] {

  val dropAfterMillis: Long // after which time do we remove entries that have not been updated
  val blackoutForMillis: Long // duration for which dropped tracks should not be re-entered
  val settleTimeMillis: Long // after which time (since sim start) do we consider updates to be at a stable rate

  val statsData: S
  val entries = MHashMap.empty[K,E]  // to keep track of last entry updates

  //-- to be provided by concrete type
  def currentSimTimeMillisSinceStart: Long
  def currentSimTimeMillis: Long
  def createTSEntryData(t: Long, o: O): E

  def processEntryData = {
    statsData.resetEntryData
    entries.foreach { e=> statsData.processEntryData(e._2) }
  }

  def snapshot (topic: String, source: String): TimeSeriesStats[O,E] = {
    new TimeSeriesStats[O, E](topic, source, currentSimTimeMillis, currentSimTimeMillisSinceStart, statsData.clone)
  }

  def dataSnapshot: S = statsData.clone.asInstanceOf[S]  // bad, but we don't want to add another type param to TSStatsData

  def updateActive (key: K, obj: O) = {
    statsData.nUpdates += 1
    val t = obj.date.toEpochMillis
    val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis

    def _add = {
      val isStale = (currentSimTimeMillis - t) > dropAfterMillis
      statsData.add(obj,isStale,isSettled)
      if (!isStale) entries += key -> createTSEntryData(t,obj)
    }

    entries.get(key) match {
      case Some(e) =>
        if (!e.removed) { // regular update
          statsData.update(obj, e, isSettled)
          e.update(obj, isSettled)
        } else { // this one might be a blackout, record and (maybe) re-enter
          if (t - e.tLast > blackoutForMillis) statsData.blackedOut(obj,e, isSettled)
          _add // re-enter
        }

      case None => _add // wasn't there yet, new entry
    }
  }

  /**
    *   this is the variant to use if removal is triggered by events that don't
    *   have update-relevant object data attached
    */
  def removeActive (key: K) = {
    entries.get(key) match {
      case Some(e) =>
        val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis
        statsData.remove(e, isSettled)
        // note we don't remove it from entries yet, we only mark the entry to
        // be removed during the next checkDropped so that we can detect blackouts
        e.removed = true

      case None => // since there was no object provided we can't enter as removed
    }
  }

  /**
    * this is the variant to use if removal is just a status attribute of events
    * that have update-relevant object data attached (e.g. if update and removal are triggered
    * by same event type)
    */
  def removeActive (key: K, obj: O) = {
    val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis
    val t = obj.date.toEpochMillis

    entries.get(key) match {
      case Some(e) =>
        // mark as removed but leave in entries so that we can detect blackouts
        statsData.remove(e, isSettled)
        e.update(obj, isSettled)
        e.removed = true

      case None =>
        // this is a new entry - add and mark as removed to be able to detect blackouts
        val isStale = (currentSimTimeMillis - t) > dropAfterMillis
        val newEntry = createTSEntryData(t,obj)
        newEntry.removed = true

        statsData.add(obj,isStale,isSettled)
        statsData.remove(newEntry, isSettled)
        if (!isStale) entries += key -> newEntry
    }
  }

  def checkDropped = {
    val t = currentSimTimeMillis
    val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis

    entries.foreach { e => // make sure we don't allocate per entry
      val entry = e._2
      if ((t - entry.tLast) > dropAfterMillis) {
        entries -= e._1
        if (!entry.removed) statsData.drop(entry, isSettled)
      }
    }
  }
}

/**
  * a TimeSeriesStatsCollector that is configured
  */
trait ConfiguredTSStatsCollector[K,O <: Dated,E <: TSEntryData[O],S <: TSStatsData[O,E]]
                                                                     extends TSStatsCollector[K,O,E,S] {
  val config: Config

  val dropAfter = config.getFiniteDurationOrElse("drop-after", 3.minutes)
  val dropAfterMillis = dropAfter.toMillis
  val blackoutFor = config.getFiniteDurationOrElse("blackout_for", 2.minutes)
  val blackoutForMillis = blackoutFor.toMillis
  val settleTimeMillis = config.getFiniteDurationOrElse("settle-time", 1.minute).toMillis

  def bucketCount: Int = config.getIntOrElse("bucket-count",0) // default is we don't collect sample distributions

  def createBuckets: Option[BucketCounter] = {
    if (bucketCount > 0) {
      val dtMin = config.getFiniteDurationOrElse("dt-min",0.seconds).toMillis
      val dtMax = config.getFiniteDurationOrElse("dt-max",dropAfter).toMillis
      Some( new BucketCounter(dtMin.toDouble,dtMax.toDouble,bucketCount))
    } else {
      None
    }
  }
}

