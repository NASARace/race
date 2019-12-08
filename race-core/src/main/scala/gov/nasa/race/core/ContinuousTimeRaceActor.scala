/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.core

import akka.actor.ActorRef
import gov.nasa.race.common.Clock
import gov.nasa.race.uom.DateTime

import scala.concurrent.duration.{Duration, FiniteDuration, MILLISECONDS}
import scala.math.max

/**
 * a RaceActor that uses simulation time and keeps track of the last time
  * the simulation clock was accessed/updated.
  * It acts both as a simClock access API and a time value cache
  *
  * Note that we share a single simTime Clock for the whole actor system
  * we are running in
 */
trait ContinuousTimeRaceActor extends RaceActor {
  private val ras = RaceActorSystem(context.system)
  val simClock: Clock = ras.simClock

  var lastSimMillis: Long = simClock.baseMillis
  var startSimTimeMillis: Long = 0
  var startWallTimeMillis: Long = 0

  override def onStartRaceActor(originator: ActorRef) = {
    startSimTimeMillis = updatedSimTimeMillis
    startWallTimeMillis = System.currentTimeMillis()
    super.onStartRaceActor(originator)
  }

  override def onSyncWithRaceClock = {
    startSimTimeMillis = simClock.millis
    lastSimMillis = startSimTimeMillis
    super.onSyncWithRaceClock
  }

  @inline def updateSimTime: Unit = lastSimMillis = simClock.millis
  @inline def simTime: DateTime = DateTime.ofEpochMillis(lastSimMillis)
  @inline def simTimeMillis: Long = lastSimMillis
  @inline def updatedSimTime: DateTime = {
    lastSimMillis = simClock.millis
    DateTime.ofEpochMillis(lastSimMillis)
  }
  @inline def updatedSimTimeMillis = {
    lastSimMillis = simClock.millis
    lastSimMillis
  }

  def updateElapsedSimTime: FiniteDuration = {
    val now = simClock.millis
    val dt = now - lastSimMillis
    lastSimMillis = now
    Duration(dt, MILLISECONDS)
  }

  // for a context in which we can't create objects
  def updateElapsedSimTimeMillis: Long = {
    val now = simClock.millis
    val dt = now - lastSimMillis
    lastSimMillis = now
    dt
  }
  def updateElapsedSimTimeMillisSince (dt: DateTime): Long = {
    lastSimMillis = simClock.millis
    lastSimMillis - dt.toEpochMillis
  }
  def updatedElapsedSimTimeMillisSinceStart: Long = {
    lastSimMillis = simClock.millis
    lastSimMillis - startSimTimeMillis
  }

  @inline final def baseSimTime: DateTime = simClock.base

  @inline final def currentSimTime: DateTime = DateTime.ofEpochMillis(simClock.millis)
  @inline final def currentSimTimeMillis = simClock.millis
  @inline final def currentWallTimeMillis = System.currentTimeMillis()

  @inline def currentSimTimeMillisSinceStart = currentSimTimeMillis - startSimTimeMillis
  @inline def currentWallTimeMillisSinceStart = currentWallTimeMillis - startWallTimeMillis

  // those are based on the last update
  def elapsedSimTimeSince (dt: DateTime) = Duration(max(0,lastSimMillis - dt.toEpochMillis), MILLISECONDS)
  def elapsedSimTimeMillisSince (dt: DateTime) = lastSimMillis - dt.toEpochMillis

  def elapsedSimTimeSinceStart = Duration(lastSimMillis - startSimTimeMillis, MILLISECONDS)
  @inline def elapsedSimTimeMillisSinceStart = lastSimMillis - startSimTimeMillis

  @inline def toWallTimeMillis (d: Duration) = (d.toMillis / simClock.timeScale).toLong
  @inline def toWallTimeMillis (ms: Long) = (ms / simClock.timeScale).toLong

  @inline def exceedsEndTime (d: DateTime) = simClock.exceedsEndTime(d)

  @inline def isStopped = simClock.isStopped
}
