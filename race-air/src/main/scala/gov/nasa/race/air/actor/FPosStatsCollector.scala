package gov.nasa.race.air.actor

import akka.actor.{ActorRef, Cancellable}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.air.{FlightCompleted, FlightDropped, FlightPos}
import gov.nasa.race.common.{BucketCounter, Stats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.geo.GreatCircle
import org.joda.time.DateTime

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration._

/**
  * actor that collects update statistics for FPos objects
  */
class FPosStatsCollector (val config: Config) extends SubscribingRaceActor with PublishingRaceActor
                      with ContinuousTimeRaceActor with PeriodicRaceActor {

  class FPosUpdates (var tLast: Long, var lastFpos: FlightPos) {
    var count: Int = 0
    var dtSum: Int = 0
    var dtMin: Int = 0
    var dtMax: Int = 0
  }

  override def defaultTickInterval = 10.seconds
  override def defaultTickDelay = 10.seconds

  val title = config.getStringOrElse("title", name)
  var channels = ""

  var firstPos = true
  val resetClockDiff = config.getOptionalFiniteDuration("reset-clock-diff") // sim time

  val dropAfter = config.getFiniteDurationOrElse("drop-after", 5.minutes).toMillis // this is sim time
  val settleTime = config.getFiniteDurationOrElse("settle-time", 2.minutes).toMillis

  val activeFlights = MHashMap.empty[String,FPosUpdates]

  var completedFlights = 0  // regularly completed flights
  var staleFlights = 0      // flights with a old position time
  var droppedFlights = 0    // flights that didn't get updated

  var minActive = 0
  var maxActive = 0

  var outOfOrder = 0  // older FlightPos objects received later
  var duplicate = 0  // FlightPos objects with identical time stamps and positions
  var ambiguous = 0   // FlightPos objects with identical time stamps but different positions

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
    case BusEvent(_, fpos: FlightPos, _) =>
      checkClockReset(fpos.date)
      updateActiveFlight(fpos)

    case BusEvent(_, fcomplete: FlightCompleted, _) =>
      checkClockReset(fcomplete.date)
      activeFlights -= fcomplete.cs
      completedFlights += 1
      updateMinActiveStats

    // we do our own flight dropped handling since we also check upon first report

    case RaceTick =>
      checkDropped
      publish(snapshot)
  }

  def checkClockReset (d: DateTime) = {
    if (firstPos) {
      ifSome(resetClockDiff) { dur =>
        if (elapsedSimTimeSince(d) > dur){
          resetSimClockRequest(d)
        }
      }
      firstPos = false
    }
  }

  def updateActiveFlight (fpos: FlightPos) = {
    val t = fpos.date.getMillis
    activeFlights.get(fpos.cs) match {
      case Some(fpu:FPosUpdates) =>
        if (t > fpu.tLast) {
          val dt = (t - fpu.tLast).toInt
          fpu.tLast = t
          fpu.lastFpos = fpos
          fpu.count += 1
          fpu.dtSum += dt
          if (dt > fpu.dtMax) fpu.dtMax = dt
          if (elapsedSimTimeMillisSinceStart > settleTime) {
            updateStats.add(dt)
            if ((dt > 0) && (dt < fpu.dtMin || fpu.dtMin == 0)) fpu.dtMin = dt
          }
        } else if (t == fpu.tLast) { // same time is either duplicate or ambiguous
          if (fpos.position =:= fpu.lastFpos.position && fpos.altitude =:= fpu.lastFpos.altitude) {
            info(s"duplicate flight pos: $fpos")
            duplicate += 1
          } else {
            val lastFpos = fpu.lastFpos
            val dist = GreatCircle.distance(fpos.position,lastFpos.position,
                                            (fpos.altitude + lastFpos.altitude) / 2)
            info(s"ambiguous flight pos: $fpos - ${lastFpos} = ${dist.toMeters.toInt}m")
            ambiguous += 1
          }
        } else { // out-of-order message - we already had a more recent position
          info(s"out of order flight pos: $fpos - ${fpu.lastFpos.date} = ${(t-fpu.tLast)/1000}s")
          outOfOrder += 1
        }

      case None => // new flight
        val tNow = updatedSimTimeMillis
        if ((tNow - t) > dropAfter) {
          info(s"stale flight pos: $fpos")
          staleFlights += 1
        } else {
          activeFlights += fpos.cs -> new FPosUpdates(t,fpos)
          updateMaxActiveStats
        }
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

  def checkDropped = {
    val t = updatedSimTimeMillis
    val cut = dropAfter
    val n = activeFlights.size

    activeFlights.foreach { e => // make sure we don't allocate per entry
      val tLast = e._2.tLast
      if ((t - tLast) > cut) {
        droppedFlights += 1
        activeFlights -= e._1
      }
    }

    if (activeFlights.size < n) updateMinActiveStats
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

    new FlightObjectStats(title, tNow, elapsedSimTimeMillisSinceStart, channels,
                          activeFlights.size,minActive,maxActive,
                          updateStats.clone,
                          completedFlights,staleFlights,droppedFlights,
                          outOfOrder,duplicate,ambiguous)
  }
}

class FlightObjectStats (val topic: String, val takeMillis: Long, val elapsedMillis: Long, val channels: String,
                         val active: Int, val minActive: Int, val maxActive: Int,
                         val bc: BucketCounter,
                         val completed: Int, val stale: Int, val dropped: Int,
                         val outOfOrder: Int, duplicate: Int, ambiguous: Int)  extends Stats {
  def time (millis: Double): String = {
    if (millis.isInfinity || millis.isNaN) {
      "     "
    } else {
      if (millis < 120000) f"${millis / 1000}%4.0fs"
      else if (millis < 360000) f"${millis / 60000}%4.1fm"
      else f"${millis / 360000}%4.1fh"
    }
  }

  def printToConsole = {
    printConsoleHeader

    println(s"observed channels: $channels")

    val dtAvg = time(bc.mean)
    val dtMin = time(bc.min)
    val dtMax = time(bc.max)

    println("active    min    max   cmplt stale  drop order   dup ambig        n dtMin dtMax dtAvg")
    println("------ ------ ------   ----- ----- ----- ----- ----- -----  ------- ----- ----- -----")
    print(f"$active%6d $minActive%6d $maxActive%6d   $completed%5d $stale%5d $dropped%5d $outOfOrder%5d $duplicate%5d $ambiguous%5d ")
    if (bc.nSamples > 0) {
      println(f"  ${bc.nSamples}%6d ${time(bc.min)} ${time(bc.max)} ${time(bc.mean)}")
      bc.processBuckets( (i,c) => {
        if (i%6 == 0) println  // 6 buckets per line
        print(f"${Math.round(i*bc.bucketSize/1000)}%3ds: $c%6d | ")
      })
      println
    } else println
  }
}
