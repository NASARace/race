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

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.{Dated, ifSome}

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration._


/**
  * user of a ConfigurableUpdateTimeSeries, which is usually a ContinuousTimeRaceActor
  */
trait TimeSeriesUpdateContext[T] {
  // usually from ContinuousTimeRaceActor
  def elapsedSimTimeMillisSinceStart: Long
  def updatedSimTimeMillis: Long

  // usually from RaceActor
  def info (msg: => String): Unit

  // abstract data interface to detect common update series problems
  def isDuplicate (current: T, last: T): Boolean
  def isAmbiguous (current: T, last: T) = !isDuplicate(current,last)
}

/**
  * the update type independent statistics data of ConfigurableUpdateTimeSeries
  * (this is what we have to clone in case of a snapshot)
  */
class UpdateStats (var buckets: Option[BucketCounter]) extends Cloneable {
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

  def nSamples = buckets match {
    case Some(bucketCounter) => bucketCounter.nSamples
    case _ => 0
  }

  def snapshot = {
    val snap = clone.asInstanceOf[UpdateStats]
    ifSome(buckets) { bc => snap.buckets = Some(bc.clone)}
    snap
  }
}

/**
  * a trait that keeps track of statistics for Dated object updates
  * this includes counts for a number of generic problems such as receiving outdated updates
  *
  * Note this is agnostic as to how updates are received
  */
class ConfigurableUpdateTimeSeries[T <: Dated] (val config: Config, val updateContext: TimeSeriesUpdateContext[T]) extends Cloneable {
  class UpdateEntry (var tLast: Long, var lastObj: T) {
    var count: Int = 0
    var dtSum: Int = 0
    var dtMin: Int = 0
    var dtMax: Int = 0
  }

  //--- configurable settings
  val dropAfter = config.getFiniteDurationOrElse("drop-after", 5.minutes).toMillis // this is sim time
  val settleTime = config.getFiniteDurationOrElse("settle-time", 30.seconds).toMillis
  val nBuckets = config.getIntOrElse("buckets",0) // default is we don't collect sample distributions

  val bucketCounter: Option[BucketCounter] =  if (nBuckets > 0) {
    val dtMin = config.getFiniteDurationOrElse("dt-min",0.seconds).toMillis // TODO - do defaults make sense here?
    val dtMax = config.getFiniteDurationOrElse("dt-max",180.seconds).toMillis
    Some( new BucketCounter(dtMin,dtMax,nBuckets))
  } else None
  val stats = new UpdateStats(bucketCounter)
  import stats._

  val activeEntries = MHashMap.empty[String,UpdateEntry]

  def snapshot = stats.snapshot

  def updateActive (key: String, obj: T) = {
    nUpdates += 1
    val t = obj.date.getMillis
    activeEntries.get(key) match {
      case Some(e:UpdateEntry) =>
        if (t > e.tLast) {
          val dt = (t - e.tLast).toInt
          e.tLast = t
          e.lastObj = obj
          e.count += 1
          e.dtSum += dt
          if (dt > e.dtMax) {
            e.dtMax = dt
            if (dt > dtMax) dtMax = dt
          }
          if (updateContext.elapsedSimTimeMillisSinceStart > settleTime) {
            ifSome(bucketCounter) {_.add(dt)}
            if ((dt > 0) && (dt < e.dtMin || e.dtMin == 0)) {
              e.dtMin = dt
              if (dt < dtMin) dtMin = dt
            }
          }
        } else if (t == e.tLast) { // same time is either duplicate or ambiguous
          val lastObj = e.lastObj
          if (updateContext.isDuplicate(obj,lastObj)) {
            updateContext.info(s"duplicate update: $obj")
            duplicate += 1
          } else { // if it's not a duplicate it means something in there is ambiguous since it has the same time stamp
            updateContext.info(s"ambiguous update: $obj - $lastObj")
            ambiguous += 1
          }
        } else { // out-of-order message - we already had a more recent position
          updateContext.info(s"out of order flight pos: $obj - ${e.lastObj.date} = ${(t-e.tLast)/1000}s")
          outOfOrder += 1
        }

      case None => // new flight
        val tNow = updateContext.updatedSimTimeMillis
        if ((tNow - t) > dropAfter) {
          updateContext.info(s"stale update: $obj")
          stale += 1
        } else {
          activeEntries += key -> new UpdateEntry(t,obj)
          updateMaxActiveStats
          nActive = activeEntries.size
        }
    }
  }

  def removeActive (key: String) = {
    // we can't check for non-active removes since we might get them as first messages on live streams
    activeEntries -= key
    completed += 1
    updateMinActiveStats
    nActive = activeEntries.size
  }

  def updateMinActiveStats = {
    if (updateContext.elapsedSimTimeMillisSinceStart > settleTime) {
      val nActive = activeEntries.size
      if (minActive == 0 || nActive < minActive) minActive = nActive
    }
  }

  def updateMaxActiveStats = {
    val nActive = activeEntries.size
    if (nActive > maxActive) maxActive = nActive
  }

  def checkDropped = {
    val t = updateContext.updatedSimTimeMillis
    val cut = dropAfter
    val n = activeEntries.size

    activeEntries.foreach { e => // make sure we don't allocate per entry
      val tLast = e._2.tLast
      if ((t - tLast) > cut) {
        dropped += 1
        activeEntries -= e._1
      }
    }

    if (activeEntries.size < n) updateMinActiveStats
  }
}

