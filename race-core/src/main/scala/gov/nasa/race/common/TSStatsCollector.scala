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
import gov.nasa.race.{Dated, _}
import gov.nasa.race.config.ConfigUtils._

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration._

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
trait TSStatsData[O <: Dated,E <: TSEntryData[O]] extends Cloneable {

  //--- global stats over all entries
  var nUpdates = 0 // number of update calls
  var dtMin = 0 // smallest update interval > 0
  var dtMax = 0 // largest update interval
  var nActive = 0 // current number of actives
  var minActive = 0 // low water mark for activeEntries (after settle-in period)
  var maxActive = 0 // high water mark for activeEntries
  var completed = 0 // entries that were explicitly removed (to see if there is re-organization in case of dropped entries)

  //--- standard problem statistics
  var stale = 0  // entries that are outdated when they are first reported (dead on arrival)
  var dropped = 0 // entries that are dropped because they didn't get updated within a certain time

  var outOfOrder = 0 // updates that have a time stamp that is older than the previous update
  var duplicate = 0 // updates that are semantically the same as the previous update
  var ambiguous = 0 // updates that have the same time stamp but are otherwise not the same as the previous update

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

  //--- standard properties support
  // NOTE - these are only called on objects that have the same timestamps, i.e. time does not have to be compared
  def isDuplicate (current: O, last: O): Boolean = current == last  // override - in most cases this is too narrow
  def isAmbiguous (current: O, last: O): Boolean = !isDuplicate(current,last)

  //--- update interface

  // new entry (might be stale already)
  def add (obj: O, isStale: Boolean, isSettled: Boolean) = {
    if (!isStale) {
      nActive += 1
      if (nActive > maxActive) maxActive = nActive
    } else {
      stale += 1
    }
  }

  // on-the-fly update of entry
  // note that the entryStats are not updated yet so that we can detect state changes
  def update (obj: O, e: E, isSettled: Boolean): Unit = {
    val dt = (obj.date.getMillis - e.tLast).toInt
    if (dt > 0){
      if (dt > dtMax) dtMax = dt
      if (isSettled) {
        if (dt < dtMin || dtMin == 0) dtMin = dt
        buckets.foreach(_.add(dt))
      }

    } else if (dt == 0) {  // duplicate or ambiguous
      if (isDuplicate(obj,e.lastObj)) duplicate += 1
      else ambiguous += 1

    } else {  // out of order
      outOfOrder += 1
    }
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
  }

  def updateMinActive(isSettled: Boolean) = if (isSettled && (minActive == 0 || nActive < minActive)) minActive = nActive
}

class TimeSeriesStats[O <: Dated,E <: TSEntryData[O]](val topic: String,
                                                      val source: String,
                                                      val takeMillis: Long,
                                                      val elapsedMillis: Long,
                                                      val data: TSStatsData[O,E]
                                                     ) extends PrintStats {
  import data._

  def printWith (pw:PrintWriter) = {
    def dur (millis: Double): String = {
      if (millis.isInfinity || millis.isNaN) {
        "-"
      } else {
        if (millis < 120000) f"${millis / 1000}%4.0fs"
        else if (millis < 360000) f"${millis / 60000}%4.1fm"
        else f"${millis / 360000}%4.1fh"
      }
    }

    pw.println("active    min    max   cmplt stale  drop order   dup ambig         n dtMin dtMax dtAvg")
    pw.println("------ ------ ------   ----- ----- ----- ----- ----- -----   ------- ----- ----- -----")
    pw.print(f"$nActive%6d $minActive%6d $maxActive%6d   $completed%5d $stale%5d $dropped%5d $outOfOrder%5d $duplicate%5d $ambiguous%5d ")

    buckets match {
      case Some(bc) if bc.nSamples > 0 =>
        pw.println(f"   ${bc.nSamples}%6d ${dur(bc.min)}%4s ${dur(bc.max)}%4s ${dur(bc.mean)}%4s")
        bc.processBuckets((i, c) => {
          if (i % 6 == 0) pw.println // 6 buckets per line
          pw.print(f"${Math.round(i * bc.bucketSize / 1000)}%3ds: $c%6d | ")
        })
      case _ => // no buckets to report
    }
    pw.println
  }
}

trait BasicTimeSeriesStats[O <: Dated] extends TimeSeriesStats[O,TSEntryData[O]]

/**
  * the per-entry time series update data for flight,track etc.
  * Note these are not per-se part of the Stats snapshots, we only store data on-the-fly here which is used to compute
  * persistent statistics. It is therefore less likely but still possible to provide a customized entry stats type
  */
class TSEntryData[O <: Dated](var tLast: Long, var lastObj: O) extends Cloneable {
  def update (obj: O, isSettled: Boolean) = {
    val t = obj.date.getMillis
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
    val t = obj.date.getMillis
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
  val settleTimeMillis: Long // after which time (since sim start) do we consider updates to be at a stable rate

  val statsData: S
  val activeEntries = MHashMap.empty[K,E]  // to keep track of last entry updates

  //-- to be provided by concrete type
  def currentSimTimeMillisSinceStart: Long
  def currentSimTimeMillis: Long
  def createTSEntryData(t: Long, o: O): E

  def snapshot (topic: String, source: String): TimeSeriesStats[O,E] = {
    new TimeSeriesStats[O, E](topic, source, currentSimTimeMillis, currentSimTimeMillisSinceStart, statsData.clone)
  }

  def dataSnapshot: S = statsData.clone.asInstanceOf[S]  // bad, but we don't want to add another type param to TSStatsData

  def updateActive (key: K, obj: O) = {
    statsData.nUpdates += 1
    val t = obj.date.getMillis
    val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis

    activeEntries.get(key) match {
      case Some(e) =>
        statsData.update(obj,e,isSettled)
        e.update(obj,isSettled)

      case None => // new flight
        val isStale = (currentSimTimeMillis - t) > dropAfterMillis
        statsData.add(obj,isStale,isSettled)

        if (!isStale) activeEntries += key -> createTSEntryData(t,obj)
    }
  }

  def removeActive (key: K) = {
    activeEntries.remove(key) match {
      case Some(e) =>
        val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis
        statsData.remove(e, isSettled)
      case None => // no stats to update
    }
  }

  def checkDropped = {
    val t = currentSimTimeMillis
    val isSettled = currentSimTimeMillisSinceStart > settleTimeMillis

    activeEntries.foreach { e => // make sure we don't allocate per entry
      if ((t - e._2.tLast) > dropAfterMillis) {
        activeEntries -= e._1
        statsData.drop(e._2, isSettled)
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

  val dropAfter = config.getFiniteDurationOrElse("drop-after", 5.minutes)
  val dropAfterMillis = dropAfter.toMillis
  val settleTimeMillis = config.getFiniteDurationOrElse("settle-time", 1.minute).toMillis

  def bucketCount: Int = config.getIntOrElse("bucket-count",0) // default is we don't collect sample distributions

  def createBuckets: Option[BucketCounter] =  if (bucketCount > 0) {
    val dtMin = config.getFiniteDurationOrElse("dt-min",0.seconds).toMillis
    val dtMax = config.getFiniteDurationOrElse("dt-max",dropAfter).toMillis
    Some( new BucketCounter(dtMin,dtMax,bucketCount))
  } else None
}

