/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.earth

import gov.nasa.race.geo.GeoMap
import gov.nasa.race.uom.{DateTime, Time}

/**
 * a GeoMap that supports the following constraints on hotspot history logs
 *
 *   - bounded history (invariant maxHistory)
 *   - last hotspot not older than maxMissing with respect to ref date
 *   - all hotspots not older than maxAge with respect to ref date
 *
 *   note that GeoMaps are mutable - the owner has to make sure access is synchronized
 */
class HotspotMap [T<:Hotspot] (decimals: Int, val maxHistory: Int, val maxAge: Time, val maxMissing: Time) extends GeoMap[Seq[T]](decimals) {

  protected var lastDate: DateTime = DateTime.UndefinedDateTime
  protected var lastReportedDate: DateTime = DateTime.UndefinedDateTime

  protected var nLastUpdate: Int = 0
  protected var nLastGood: Int = 0
  protected var nLastProbable: Int = 0
  protected var changed: Boolean = false

  def getLastDate: DateTime = lastDate

  def getLastUpdateCount: Int = nLastUpdate
  def getLastGoodCount: Int = nLastGood
  def getLastProbableCount: Int = nLastProbable

  def resetNlast(): Unit = {
    nLastUpdate = 0
    nLastGood = 0
    nLastProbable = 0
  }

  def hasChanged: Boolean = changed
  def resetChanged(): Unit = changed = false

  override def addOneRaw (k: Long, v: Seq[T]): elems.type = {
    changed = true
    super.addOneRaw(k,v)
  }

  def updateWith ( h: T): Unit = {
    val k = key(h.position)
    elems.get(k) match {
      case Some(hs) => addOneRaw(k, h +: hs.take(maxHistory-1))
      case None => addOneRaw(k, Seq(h))
    }

    if (h.date > lastDate) {
      lastDate = h.date
      resetNlast()
    }

    if (h.date == lastDate) {
      nLastUpdate += 1
      if (h.isGoodPixel) nLastGood += 1
      else if (h.isProbablePixel) nLastProbable += 1
    }
  }

  def purgeOldHotspots (d: DateTime): Boolean = {

    //--- purge entries that haven't been updated for at least maxMissing
    foreachRaw { (k,hs) =>
      if (hs.isEmpty || d.timeSince(hs.head.date) > maxMissing) {
        removeRaw(k)
        changed = true
      }
    }

    //--- purge all entry hotspots that are older than maxAge
    foreachRaw { (k,hs) =>
      val hsKeep = hs.filter( h=> d.timeSince(h.date) < maxAge)
      if (hsKeep ne hs) {
        if (hsKeep.isEmpty){
          removeRaw(k)
          changed = true
        } else {
          addOneRaw(k, hsKeep)
          changed = true
        }
      }
    }

    changed
  }
}
