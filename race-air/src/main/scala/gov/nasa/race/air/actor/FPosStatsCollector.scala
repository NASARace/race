package gov.nasa.race.air.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.air.{FlightCompleted, FlightDropped, FlightPos}
import gov.nasa.race.common.{BucketCounter, Stats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration._

/**
  * actor that collects update statistics for FPos objects
  */
class FPosStatsCollector (val config: Config) extends SubscribingRaceActor with PublishingRaceActor
                      with ContinuousTimeRaceActor with PeriodicRaceActor {
  class FPosUpdates (var tLast: Long) {
    var count: Int = 0
    var dtSum: Int = 0
    var dtMin: Int = 0
    var dtMax: Int = 0
  }

  override def defaultTickInterval = 10.seconds
  override def defaultTickDelay = 10.seconds

  val title = config.getStringOrElse("title", name)
  var channels = ""
  val settleTime = config.getFiniteDurationOrElse("settle-time", 2.minutes).toMillis

  val activeFlights = MHashMap.empty[String,FPosUpdates]
  var droppedFlights = 0
  var completedFlights = 0
  var minActive = 0
  var maxActive = 0
  var outOfOrder = 0

  val updateStats = new BucketCounter(
    config.getFiniteDurationOrElse("dt-min",0.seconds).toMillis,
    config.getFiniteDurationOrElse("dt-max",180.seconds).toMillis,
    config.getIntOrElse("dts",18)
  )

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    channels = readFromAsString
    startScheduler
  }

  override def handleMessage = {
    case BusEvent(_, fpos: FlightPos, _) => updateActiveFlight(fpos)

    case BusEvent(_, fcomplete: FlightCompleted, _) =>
      activeFlights -= fcomplete.cs
      completedFlights += 1
      updateMinActiveStats

    case BusEvent(_, fdropped: FlightDropped, _) =>
      activeFlights -= fdropped.cs
      droppedFlights += 1
      updateMinActiveStats

    case RaceTick => publish(snapshot)
  }

  def updateActiveFlight (fpos: FlightPos) = {
    val t = fpos.date.getMillis
    activeFlights.get(fpos.cs) match {
      case Some(fpu:FPosUpdates) =>
        if (t >= fpu.tLast) {
          val dt = (t - fpu.tLast).toInt
          fpu.tLast = t
          fpu.count += 1
          fpu.dtSum += dt
          if (dt > fpu.dtMax) fpu.dtMax = dt
          if (elapsedSimTimeMillisSinceStart > settleTime) {
            updateStats.add(dt)
            if ((dt > 0) && (dt < fpu.dtMin || fpu.dtMin == 0)) fpu.dtMin = dt
          }
        } else {
          outOfOrder += 1
        }

      case None =>
        activeFlights += fpos.cs -> new FPosUpdates(t)
        updateMaxActiveStats
    }
  }

  def updateMinActiveStats = {
    if (elapsedSimTimeMillisSinceStart > settleTime) {
      val nActive = activeFlights.size
      if (minActive == 0 || nActive < minActive) minActive = nActive
    }
  }

  def updateMaxActiveStats = {
    val nActive = activeFlights.size
    if (nActive > maxActive) maxActive = nActive
  }

  def snapshot: Stats = {
    val tNow = updatedSimTimeMillis
    var nUpdated = 0
    var avgSum = 0
    var dtMin = 0
    var dtMax = 0

    for (fpu <- activeFlights.values) {
      if (fpu.count > 0) {
        avgSum += fpu.dtSum / fpu.count // add average update interval for this flight
        nUpdated += 1
        if (elapsedSimTimeMillisSinceStart > settleTime) {
          if ((fpu.dtMin > 0) && ((fpu.dtMin < dtMin) || (dtMin == 0))) {
            dtMin = fpu.dtMin
          }
        }
        if (fpu.dtMax > dtMax) dtMax = fpu.dtMax
      }
    }

    val nActive = activeFlights.size
    val avgUpdate = if (nUpdated > 0) avgSum / nUpdated  else 0

    new FlightObjectStats(title, tNow, elapsedSimTimeMillisSinceStart, channels,
        nActive,minActive,maxActive,
        updateStats.clone,
        completedFlights,droppedFlights,outOfOrder)
  }
}

class FlightObjectStats (val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                         val active: Int, val minActive: Int, val maxActive: Int,
                         val bc: BucketCounter,
                         val completed: Int, val dropped: Int, val outOfOrder: Int)  extends Stats {
  def time (millis: Double): String = {
    if (millis.isInfinity || millis.isNaN) {
      "     "
    } else {
      if (millis < 120000) f"${millis / 1000}%5.0fs"
      else if (millis < 360000) f"${millis / 60000}%5.1fm"
      else f"${millis / 360000}%5.1fh"
    }
  }

  def printToConsole = {
    printConsoleHeader

    println(s"observed channels: $channels")

    val dtAvg = time(bc.mean)
    val dtMin = time(bc.min)
    val dtMax = time(bc.max)

    println("active    min    max   complt   drop  order         n  dtMin  dtMax  dtAvg   sigma")
    println("------ ------ ------   ------ ------ ------   ------- ------ ------ ------ -------")
    print(f"$active%6d $minActive%6d $maxActive%6d   $completed%6d $dropped%6d $outOfOrder%6d    ")
    if (bc.nSamples > 0) {
      println(f"${bc.nSamples}%6d ${time(bc.min)} ${time(bc.max)} ${time(bc.mean)} ${bc.sigma}%7.2f")
      bc.processBuckets( (i,c) => {
        if (i%6 == 0) println  // 6 buckets per line
        print(f"${Math.round(i*bc.bucketSize/1000)}%3ds: $c%6d | ")
      })
      println
    } else println
  }
}
