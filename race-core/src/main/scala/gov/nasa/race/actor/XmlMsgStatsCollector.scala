package gov.nasa.race.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.common.{ElemStats, MsgStats, SubscriberMsgStats}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, SubscribingRaceActor}
import gov.nasa.race.util.XmlPullParser

import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.concurrent.duration._
import scala.util.matching.Regex

/**
  * a SubscribingRaceActor that reports number, size and average / peak rates for each message
  * type it receives on its input channels
  *
  * Instances can be optionally configured with path matchers for XML message elements, such
  * as "ns5:MessageCollection/message/flight/enRoute/**/location/pos"
  *
  * XmlMsgStatsCollector does only collect and publish statistics. Reporting has to be done with
  * a dedicated actor
  *
  * Note that we don't use Akka Agents here although this is a classic synchronous state access
  * problem from a client's perspective. The rationale is that we assume reporting happens much less
  * frequently than statistics update, hence we cannot afford a immutable type during updates
  * (we don't want to allocate a new object just to increase a single Int)
  */
class XmlMsgStatsCollector (val config: Config) extends SubscribingRaceActor with PublishingRaceActor
         with ContinuousTimeRaceActor with PeriodicRaceActor {

  final val defaultRateBaseMillis = 2000 // get peak rate over 2 sec

  // override the MonitoredRaceActor defaults
  override def defaultTickInterval = 10.seconds
  override def defaultTickDelay = 10.seconds

  val title = config.getStringOrElse("title", name)
  val patternSpecs = config.getStringArray("patterns") // optional, if none we only parse top level
  val ignoreMessages = config.getStringArray("ignore").map(new Regex(_))

  // the time base we use for msg peak rate calculation
  // Note - this can't be calculated from the time we process a message since Akka might not schedule
  // this actor until it has a number of pending messages, which means there would be no time gap
  // and hence an infinite peak rate
  implicit val rateBaseMillis = config.getIntOrElse("rate-base", defaultRateBaseMillis)

  val msgStats = MSortedMap.empty[String,MsgStats]
  var channels = "" // set during start

  class Parser extends XmlPullParser {
    val pathQueries = patternSpecs map( ps => compileGlobPathQuery(ps.split("/")))
    setBuffer(new Array[Char](config.getIntOrElse("buffer-size", 4096))) // pre-alloc buffer

    def parse(input: String) = {
      initializeBuffered(input)

      var isFirstElement = true
      var msgStat: MsgStats = null
      var done = false

      while (!done && parseNextElement) {
        if (isStartElement) {
          if (isFirstElement){
            if (ignoreMessage(tag)) {
              done = true
            } else {
              isFirstElement = false
              msgStat = msgStats.getOrElseUpdate(tag, new MsgStats(tag))
              msgStat.update(updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, input.length)
              if (pathQueries.isEmpty) done = true // no need to parse elements
            }

          } else { // we have pathQueries. Avoid allocation, this can be called frequently
            var idx = 0
            pathQueries foreach { pqId =>
              if (isMatchingPath(pqId)) {
                val pattern = patternSpecs(idx)
                val elemStat = msgStat.elems.getOrElseUpdate(pattern,new ElemStats(pattern))
                elemStat.count += 1
              }
              idx += 1
            }
          }
        }
      }
    }
  }

  val parser = new Parser

  def ignoreMessage (msgName: String): Boolean = {
    ignoreMessages foreach { re => if (re.findFirstIn(msgName).isDefined) return true }
    false
  }

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)
    channels = readFromAsString
    startScheduler
  }

  override def handleMessage = {
    case BusEvent(_,msg: String,_) => parser.parse(msg)
    case RaceTick if hasPublishingChannels => publish(snapshot)
  }

  def snapshot = new SubscriberMsgStats(title, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart,
                                        channels, mapIteratorToArray(msgStats.valuesIterator,msgStats.size)(_.snapshot))
}

