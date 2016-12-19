package gov.nasa.race.air.actor

import java.io.PrintStream

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.air.{FlightCompleted, FlightDropped, FlightPos}
import gov.nasa.race.common.{Stats, StatsPrinter}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.RaceCheck
import gov.nasa.race.core.{BusEvent, ContinuousTimeRaceActor, MonitoredRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.DateTimeUtils.durationMillisToHMMSS

import scala.collection.mutable.{HashMap => MHashMap}
import scala.concurrent.duration._

/**
  * actor that collects update statistics for FPos objects
  */
class FPosStatsCollector (val config: Config) extends SubscribingRaceActor with PublishingRaceActor
                      with ContinuousTimeRaceActor with MonitoredRaceActor {
  class FPosUpdates (
    var tLast: Long,
    var count: Int=0,
    var dtSum: Int=0,
    var dtMin: Int=0,
    var dtMax: Int=0
  )

  override def defaultCheckInterval = 10.seconds
  override def defaultCheckDelay = 10.seconds

  val title = config.getStringOrElse("title", name)
  var channels = ""
  val settleTime = config.getFiniteDurationOrElse("settle-time", 2.minutes).toMillis

  val activeFlights = MHashMap.empty[String,FPosUpdates]
  var droppedFlights = 0
  var completedFlights = 0
  var minActive = 0
  var maxActive = 0
  var outOfOrder = 0

  StatsPrinter.registerPrinter(classOf[FlightObjectStats], new FlightObjectStatsPrinter)

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    channels = readFromAsString
    startMonitoring
  }

  override def handleMessage = {
    case BusEvent(_, fpos: FlightPos, _) => updateActiveFlights(fpos)

    case BusEvent(_, fcomplete: FlightCompleted, _) =>
      activeFlights -= fcomplete.cs
      completedFlights += 1
      updateMinActiveStats

    case BusEvent(_, fdropped: FlightDropped, _) =>
      activeFlights -= fdropped.cs
      droppedFlights += 1
      updateMinActiveStats

    case RaceCheck => publish(snapshot)
  }

  def updateActiveFlights (fpos: FlightPos) = {
    val t = fpos.date.getMillis
    activeFlights.get(fpos.cs) match {
      case Some(fpu:FPosUpdates) =>
        if (t > fpu.tLast) {
          val dt = (t - fpu.tLast).toInt
          fpu.tLast = t
          fpu.count += 1
          fpu.dtSum += dt
          if (dt > fpu.dtMax) fpu.dtMax = dt
          if (elapsedSimTimeMillisSinceStart > settleTime) {
            if (dt < fpu.dtMin || fpu.dtMin == 0) fpu.dtMin = dt
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
          if (fpu.dtMin < dtMin || dtMin == 0) dtMin = fpu.dtMin
        }
        if (fpu.dtMax > dtMax) dtMax = fpu.dtMax
      }
    }
    val nActive = activeFlights.size
    val avgUpdate = if (nUpdated > 0) avgSum / nUpdated  else 0

    Stats(title,
      tNow,
      elapsedSimTimeMillisSinceStart,
      FlightObjectStats(channels,
        nActive,minActive,maxActive,
        avgUpdate,dtMin,dtMax,
        completedFlights,droppedFlights,outOfOrder)
    )
  }
}

case class FlightObjectStats (
  channels: String,
  active: Int,
  minActive: Int,
  maxActive: Int,
  avgUpdateMillis: Int,
  minUpdateMillis: Int,
  maxUpdateMillis: Int,
  completed: Int,
  dropped: Int,
  outOfOrder: Int
)

class FlightObjectStatsPrinter extends StatsPrinter {
  def show(stats: Stats, ps: PrintStream = Console.out) = {
    stats.data match {
      case d: FlightObjectStats =>
        showTopic(stats, ps)

        ps.println(s"observed channels: ${d.channels}")
        ps.println(s"observed duration: ${durationMillisToHMMSS(stats.elapsedMillis)}")

        val dtAvg = d.avgUpdateMillis / 1000.0
        val dtMin = d.minUpdateMillis / 1000.0
        val dtMax = d.maxUpdateMillis / 1000.0

        ps.println(" active     min     max    complt    drop   order   dtAvg  dtMin  dtMax")
        ps.println("------- ------- -------   ------- ------- -------  ------ ------ ------")
        ps.println(f"${d.active}%7d ${d.minActive}%7d ${d.maxActive}%7d   ${d.completed}%7d ${d.dropped}%7d ${d.outOfOrder}%7d  $dtAvg%6.1f $dtMin%6.1f $dtMax%6.1f")
        ps.println

      case other => // ignore
    }
  }
}
