package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.TranslatorActor
import gov.nasa.race.air.translator.SBS2FlightPos
import gov.nasa.race.air._
import gov.nasa.race.config.ConfigUtils._

import scala.collection.mutable.{HashMap => MHashMap}

/**
  * a SBS to FlightPos translator with built-in support for sanity checks, delayed callsign
  * changes, and FlightDropped generation for stale FlightPos objects. Effectively, this is
  * also a optional EitherOrRouter (if sanity violations are recorded), but that trait is not
  * worth mixing in separately
  *
  * This breaks a bit with the small dedicated actor paradigm for scalability reasons, since
  * all secondary functions (checks and c/s changes) require to keep maps of active
  * FlightPos objects that have update intervals of > 1Hz
  */
class SBSTranslatorActor (config: Config) extends TranslatorActor(config) with FPosDropper {

  val timeCut = config.getIntOrElse("time-cut-ms", 0) // in millis, non-ambiguous positions with lower dt are ignored
  val passSanityViolations = config.getBooleanOrElse ("pass-failed", true)
  val writeToFail = config.getOptionalString("write-to-fail")
  val sanityChecker = getConfigurableOrElse[FlightPosChecker]("checker", EmptyFlightPosChecker)

  val flights = MHashMap.empty[String,FlightPos]

  override def createTranslator = new SBS2FlightPos(config)  // for TranslatorActor

  override def handleMessage = handleTranslatorMessage orElse handleFPosDropperMessage

  override def removeStaleFlight (fpos: FlightPos) = flights -= fpos.cs // for FPosDropper

  // specialized translation processing, called from TranslatorActor
  override def processTranslationProduct(o: Any) = o match {
    case fpos: FlightPos =>
      val cs = fpos.cs
      flights.get(cs) orElse getReplacedCSFlight(fpos) match {
        case Some(lastFPos) => checkAndUpdate(fpos,lastFPos)
        case None => checkAndAdd(fpos)
      }
    case _ => // ignore translation product, we only publish FlightPos objects
  }

  def getReplacedCSFlight(fpos: FlightPos): Option[FlightPos] = for {
    oldCS <- fpos.getOldCS
    lastFPos <- flights.get(oldCS)
  } yield {
    flights -= oldCS
    info(s"changing c/s of $oldCS to ${fpos.cs}")
    publish(FlightCsChanged(fpos.id,fpos.cs, oldCS, fpos.date))
    lastFPos
  }

  def checkAndAdd (fpos: FlightPos) = {
    processCheckResult(fpos, sanityChecker.check(fpos),Long.MaxValue)
  }

  def checkAndUpdate (fpos: FlightPos, lastFPos: FlightPos) = {
    processCheckResult(fpos, sanityChecker.checkPair(fpos,lastFPos),dtMillis(fpos,lastFPos))
  }

  @inline def dtMillis(fpos: FlightPos, lastFPos: FlightPos) = fpos.date.getMillis - lastFPos.date.getMillis

  def processCheckResult(fpos: FlightPos, checkResult: Option[FlightPosProblem], dt: Long) = {
    checkResult match {
      case Some(problem) =>
        reportProblem(problem)
        if (passSanityViolations) updateAndPublish(fpos)
      case None => // no problem, but might be considered redundant noise
        if (dt > timeCut) updateAndPublish(fpos)
    }
  }

  def updateAndPublish(fpos: FlightPos) = {
    flights.update(fpos.cs, fpos)
    publish(fpos)
  }

  def reportProblem (checkResult: FlightPosProblem) = {
    info(s"inconsistent SBS FlightPos: ${checkResult.problem}")
    ifSome(writeToFail) { channel =>
      val msg = new StringBuilder
      msg.append(s"--- SBS position inconsistency: ${checkResult.problem}\n")
      msg.append(s"current: ${checkResult.fpos}\n")
      msg.append(s"last:    ${checkResult.lastFpos}\n")  // might be null
      publish(channel, msg.toString())
    }
  }
}
