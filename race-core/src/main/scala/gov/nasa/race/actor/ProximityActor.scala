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

package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config._
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, SubscribingRaceActor}
import gov.nasa.race.geo.{GeoPosition, GeoUtils}
import gov.nasa.race.track._
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.{HashMap => MHashMap, Set => MSet}

/**
  * policy object to identify proximity events
  */
trait ProximityIdentifier {
  val config: Config
  def getId(pr: ProximityReference, track: Tracked3dObject, status: Int): String
}

trait PrefixedProximityIdentifier extends ProximityIdentifier {
  val prefix = config.getStringOrElse("event-prefix", "Proximity")
}

trait NumberedProximityIdentifier extends ProximityIdentifier {
  var n = 0

  def appendNumber (s: String) = {
    n += 1
    s"$s-$n"
  }
}

class StaticProximityId (val config: Config) extends PrefixedProximityIdentifier {
  override def getId(pr: ProximityReference, track: Tracked3dObject, status: Int) = prefix
}

class NumberedStaticProximityId  (val config: Config) extends PrefixedProximityIdentifier with NumberedProximityIdentifier {
  override def getId(pr: ProximityReference, track: Tracked3dObject, status: Int) = appendNumber(prefix)
}

class ProximityRefId  (val config: Config) extends ProximityIdentifier {
  override def getId(pr: ProximityReference, track: Tracked3dObject, status: Int) = pr.id
}

class NumberedProximityRefId (val config: Config) extends NumberedProximityIdentifier {
  override def getId(pr: ProximityReference, track: Tracked3dObject, status: Int) = appendNumber(pr.id)
}

class ProximityCombinationId (val config: Config) extends ProximityIdentifier {
  override def getId(pr: ProximityReference, track: Tracked3dObject, status: Int) = {
    val refId = pr.id
    val trackId = track.gid
    if (refId > trackId) s"$trackId-$refId" else s"$refId-$trackId"
  }
}

/**
  * base type for actors that detect and report proximity tracks
  */
trait ProximityActor extends SubscribingRaceActor with PublishingRaceActor {
  abstract class RefEntry {
    val proximities: MHashMap[String,Tracked3dObject] = MHashMap.empty

    def updateRef (t: Tracked3dObject): Unit = {}
    def checkProximity (t: Tracked3dObject): Unit
    def dropProximity(tId: String, date: DateTime): Unit
  }

  val proximityIdentifier = getConfigurableOrElse[ProximityIdentifier]("identifier")(new NumberedProximityRefId(NoConfig))
  val proximityType = config.getStringOrElse("proximity-type", defaultProximityType)
  def defaultProximityType: String = "proximity"

  // the distance we consider as proximity
  def defaultDistance: Length = NauticalMiles(5)
  val distanceInMeters = config.getLengthOrElse("distance", defaultDistance).toMeters

  val refs: MHashMap[String,RefEntry] = MHashMap.empty

  def updateProximities(track: Tracked3dObject): Unit = refs.foreach( _._2.checkProximity(track))

  def dropRef (ttm: TrackTerminationMessage) = refs -= ttm.cs

  def dropProximities (ttm: TrackTerminationMessage) = refs.foreach( _._2.dropProximity(ttm.cs,ttm.date))

  protected def createProximityEvent (pr: ProximityReference, dist: Length, status: Int, track: Tracked3dObject): ProximityEvent = {
    val id = proximityIdentifier.getId(pr,track,status)
    new ProximityEvent(id,proximityType,pr,dist,status,track)
  }
}

/**
  * a ProximityActor with configured, static references
  */
class StaticProximityActor (val config: Config) extends ProximityActor {
  import ProximityEvent._

  class StaticRefEntry (val id: String, pos: GeoPosition) extends RefEntry {

    def getDistanceInMeters (track: Tracked3dObject): Double = {
      val tLat = track.position.φ.toRadians
      val tLon = track.position.λ.toRadians
      GeoUtils.euclideanDistanceRad(pos.φ.toRadians,pos.λ.toRadians,tLat,tLon,pos.altitude.toMeters)
    }

    override def checkProximity (track: Tracked3dObject) = {
      val tId = track.cs
      val dist = getDistanceInMeters(track)

      if ((dist <= distanceInMeters) && !track.isDroppedOrCompleted) {
        val flags = if (proximities.contains(tId)) ProxChange else ProxNew
        proximities += (tId -> track)
        publish(createProximityEvent(new ProximityReference(id,track.date,pos), Meters(dist), flags, track))
      } else {
        if (proximities.contains(tId)) {
          proximities -= tId
          publish(createProximityEvent(new ProximityReference(id,track.date,pos), Meters(dist), ProxDrop, track))
        }
      }
    }

    override def dropProximity (tId: String, date: DateTime) = {
      ifSome(proximities.get(tId)) { track =>
        val dist = getDistanceInMeters(track)
        proximities -= tId
        publish(createProximityEvent(new ProximityReference(id,date,pos), Meters(dist), ProxDrop, track))
      }
    }
  }

  // override in subclasses that have well known locations and hence don't need lat/lon/alt (e.g. Airports in .air)
  protected def initRefs: Unit = {
    config.getConfigArray("refs") foreach { conf =>
      val id = conf.getString("id") // mandatory
      for (lat <- conf.getOptionalDouble("lat");
           lon <- conf.getOptionalDouble("lon");
           alt <- conf.getOptionalDouble("altitude-ft")) {
        refs += (id -> new StaticRefEntry(id, GeoPosition.fromDegreesAndFeet(lat,lon,alt)))
      }
    }
  }

  override def handleMessage = {
    case BusEvent(_,track:Tracked3dObject,_) => updateProximities(track)
  }
}

/**
  * actor that reports proximity track updates
  *
  * this actor subscribes to two sets of input channels - an optional one for reference object updates,
  * and the regular 'read-from' on which potential proximitiy tracks are reported.
  * It acts as sort of a decorating filter
  *
  * the actor can manage a number of reference tracks and publishes ProximityUpdate messages each time a track update arrives
  * that is within a configured distance of one of the managed references
  */
class DynamicProximityActor (val config: Config) extends ProximityActor {
  import ProximityEvent._

  class DynamicRefEntry (val refEstimator: TrackedObjectEstimator) extends RefEntry {
    override def updateRef (track: Tracked3dObject) = refEstimator.addObservation(track)

    protected def getDistanceInMeters (track: Tracked3dObject): Double = {
      val re = refEstimator
      val tLat = track.position.φ.toRadians
      val tLon = track.position.λ.toRadians

      GeoUtils.euclideanDistanceRad(re.lat.toRadians,re.lon.toRadians, tLat,tLon, re.altitude.toMeters)
    }

    override def checkProximity (track: Tracked3dObject) = {
      val re = refEstimator
      val tId = track.cs

      if (tId != re.track.cs) {  // don't try to be a proximity to yourself
        if (re.estimateState(track.date.toEpochMillis)) {
          val dist = getDistanceInMeters(track)

          if ((dist <= distanceInMeters) && !track.isDroppedOrCompleted) {
            val flags = if (proximities.contains(tId)) ProxChange else ProxNew
            proximities += (tId -> track)
            publish(createProximityEvent(new ProximityReference(re, track.date), Meters(dist), flags, track))
          } else {
            if (proximities.contains(tId)) {
              proximities -= tId
              publish(createProximityEvent(new ProximityReference(re, track.date), Meters(dist), ProxDrop, track))
            }
          }
        }
      }
    }

    override def dropProximity (tId: String, date: DateTime) = {
      ifSome(proximities.get(tId)) { track =>
        if (refEstimator.estimateState(date.toEpochMillis)) {
          val dist = getDistanceInMeters(track)
          proximities -= tId
          publish(createProximityEvent(new ProximityReference(refEstimator, date), Meters(dist), ProxDrop, track))
        }
      }
    }
  }

  // our second input channel set (where we get the reference objects from)
  val readRefFrom = MSet.empty[String]

  // used to (optionally) estimate reference positions when receiving proximity updates
  val refEstimatorPrototype: TrackedObjectEstimator = getConfigurableOrElse("estimator")(new HoldEstimator)


  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    // we could check for readFrom channel identity, but the underlying subscription storage uses sets anyways
    readRefFrom ++= actorConf.getOptionalStringList("read-ref-from")
    readRefFrom.foreach { channel => busFor(channel).subscribe(self,channel) }
    super.onInitializeRaceActor(raceContext, actorConf)
  }

  override def handleMessage = {
    case BusEvent(chan:String,track:Tracked3dObject,_) =>
      // note that both refs and proximities might be on the same channel
      if (readRefFrom.contains(chan)) updateRef(track)
      if (readFrom.contains(chan)) updateProximities(track)

    case BusEvent(chan:String,te:TrackTerminationMessage,_) =>
      if (readRefFrom.contains(chan)) dropRef(te)
      if (readFrom.contains(chan)) dropProximities(te)

    case BusEvent(_,msg:Any,_) =>  // all other BusEvents are ignored
  }

  def updateRef(track: Tracked3dObject): Unit = {
    if (track.isDroppedOrCompleted) {
      refs -= track.id
    } else {
      val e = refs.getOrElseUpdate(track.id, new DynamicRefEntry(refEstimatorPrototype.clone))
      e.updateRef(track)
    }
  }
}

/**
  * this is essentially a DynamicProximityActor that does not have to know if something is a new or dropped
  * proximity, hence it does not have to maintain a proximities collection
  *
  * note this implementation allows for multiple collisions to occur, even with the same track as long as there
  * is at least one separation event between the collisions. Only the entry of a track into the collision radius
  * is reported, i.e. we should not get consecutive collision events while the track is within this radius
  */
class CollisionDetector (config: Config) extends DynamicProximityActor(config) {
  import ProximityEvent._

  override def defaultProximityType: String = "collision"
  override def defaultDistance = Feet(500) // NMAC distance

  class CollisionRefEntry(refEstimator: TrackedObjectEstimator) extends DynamicRefEntry(refEstimator) {
    var collisions = Set.empty[String] // keep track of reported/ongoing collisions

    override def checkProximity (track: Tracked3dObject) = {
      val re = refEstimator
      val tcs = track.cs

      if (tcs != re.track.cs) { // don't try to be a proximity to yourself
        if (re.estimateState(track.date.toEpochMillis)) {
          val dist = getDistanceInMeters(track)
          if (dist <= distanceInMeters) {
            if (!collisions.contains(tcs)) {
              collisions = collisions + tcs
              publish(createProximityEvent(new ProximityReference(re, track.date), Meters(dist), ProxCollision, track))
            }
          } else {
            if (collisions.nonEmpty) collisions = collisions - tcs
          }
        }
      }
    }
  }

  override def updateRef(track: Tracked3dObject): Unit = {
    val e = refs.getOrElseUpdate(track.id, new CollisionRefEntry(refEstimatorPrototype.clone))
    e.updateRef(track)
  }
}