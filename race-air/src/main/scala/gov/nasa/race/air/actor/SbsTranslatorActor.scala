package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.{TrackDropper, TranslatorActor}
import gov.nasa.race.air.translator.SBS2FlightPos
import gov.nasa.race.air._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.track.{TrackCsChanged, Tracked3dObject, TrackedObject}
import gov.nasa.race.track.TrackedObject.TrackProblem

import scala.collection.mutable.{HashMap => MHashMap}

/**
  * a SBS to TrackedAircraft translator with built-in support for sanity checks, delayed callsign
  * changes, and FlightDropped generation for stale TrackedAircraft objects. Effectively, this is
  * also a optional EitherOrRouter (if sanity violations are recorded), but that trait is not
  * worth mixing in separately
  *
  * This breaks a bit with the small dedicated actor paradigm for scalability reasons, since
  * all secondary functions (checks and c/s changes) require to keep maps of active
  * TrackedAircraft objects that have update intervals of > 1Hz
  */
class SbsTranslatorActor(config: Config) extends TranslatorActor(config) with TrackDropper {

  val timeCut = config.getIntOrElse("time-cut-ms", 0) // in millis, non-ambiguous positions with lower dt are ignored
  val passSanityViolations = config.getBooleanOrElse ("pass-failed", true)
  val writeToFail = config.getOptionalString("write-to-fail")
  val sanityChecker = getConfigurableOrElse[FlightPosChecker]("checker"){ EmptyFlightPosChecker }

  val tracks = MHashMap.empty[String,TrackedAircraft]

  override def createTranslator = new SBS2FlightPos(config)  // for TranslatorActor

  override def handleMessage = handleTranslatorMessage orElse handleFPosDropperMessage

  override def removeStaleTrack (fpos: TrackedObject) = tracks -= fpos.cs // for FPosDropper

  // specialized translation processing, called from TranslatorActor
  override def processTranslationProduct(o: Any) = o match {
    case fpos: TrackedAircraft =>
      val cs = fpos.cs
      tracks.get(cs) orElse getReplacedCSFlight(fpos) match {
        case Some(lastFPos) => checkAndUpdate(fpos,lastFPos)
        case None => checkAndAdd(fpos)
      }
    case _ => // ignore translation product, we only publish TrackedAircraft objects
  }

  def getReplacedCSFlight(fpos: TrackedAircraft): Option[TrackedAircraft] = for {
    oldCS <- fpos.getOldCS
    lastFPos <- tracks.get(oldCS)
  } yield {
    tracks -= oldCS
    info(s"changing c/s of $oldCS to ${fpos.cs}")
    publish(TrackCsChanged(fpos.id,fpos.cs, oldCS, fpos.date))
    lastFPos
  }

  def checkAndAdd (fpos: TrackedAircraft) = {
    processCheckResult(fpos, sanityChecker.check(fpos),Long.MaxValue)
  }

  def checkAndUpdate (fpos: TrackedAircraft, lastFPos: TrackedAircraft) = {
    processCheckResult(fpos, sanityChecker.checkPair(fpos,lastFPos),dtMillis(fpos,lastFPos))
  }

  @inline def dtMillis(fpos: TrackedAircraft, lastFPos: TrackedAircraft) = fpos.date.toEpochMillis - lastFPos.date.toEpochMillis

  def processCheckResult(fpos: TrackedAircraft, checkResult: Option[TrackProblem], dt: Long) = {
    checkResult match {
      case Some(problem) =>
        reportProblem(problem)
        if (passSanityViolations) updateAndPublish(fpos)
      case None => // no problem, but might be considered redundant noise
        if (dt > timeCut) updateAndPublish(fpos)
    }
  }

  def updateAndPublish(fpos: TrackedAircraft) = {
    tracks.update(fpos.cs, fpos)
    publish(fpos)
  }

  def reportProblem (checkResult: TrackProblem) = {
    info(s"inconsistent SBS TrackedAircraft: ${checkResult.problem}")
    ifSome(writeToFail) { channel =>
      val msg = new StringBuilder
      msg.append(s"--- SBS position inconsistency: ${checkResult.problem}\n")
      msg.append(s"current: ${checkResult.fpos}\n")
      msg.append(s"last:    ${checkResult.lastFpos}\n")  // might be null
      publish(channel, msg.toString())
    }
  }
}
