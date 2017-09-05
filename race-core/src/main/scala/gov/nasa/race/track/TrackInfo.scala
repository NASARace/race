package gov.nasa.race.track

import gov.nasa.race._
import gov.nasa.race.config.{NamedConfigurable,NoConfig}

import com.typesafe.config.Config
import org.joda.time.DateTime
import scala.collection.concurrent.TrieMap

/**
  * meta info for tracks, such as departure and arrival location and times, flight plans etc.
  * This is supposed to be an accumulator that can be updated from different sources to collect all
  * information about a certain track, hence it depends on global ids ('cs')
  *
  * note we don't keep this as a case class since we want to be able to extend it
  * but the identifiers are optional since they can trickle in over time. This is supposed to
  * be an accumulative data structure
  */
class TrackInfo(val trackRef: String,
                val cs: String,

                val trackCategory: Option[String],
                val trackType: Option[String],

                val departurePoint: Option[String],
                val arrivalPoint: Option[String],

                val etd: Option[DateTime],
                val atd: Option[DateTime],
                val eta: Option[DateTime],
                val ata: Option[DateTime],

                val route: Option[Trajectory]
               ) {

  override def toString = { // compact version
    val sb = new StringBuilder
    sb.append(s"TrackInfo{cs:$cs")
    ifSome(trackType){ s=> sb.append(s",type:$s")}
    ifSome(departurePoint){ s=> sb.append(s",from:$s")}
    ifSome(arrivalPoint){ s=> sb.append(s",to:$s")}
    if (atd.isDefined) sb.append(s",atd:${atd.get}") else if (etd.isDefined) sb.append(s",etd:${etd.get}")
    if (ata.isDefined) sb.append(s",ata:${ata.get}") else if (eta.isDefined) sb.append(s",eta:${eta.get}")
    sb.append('}')
    sb.toString
  }

  def accumulate (prev: TrackInfo): TrackInfo = {
    new TrackInfo( trackRef, cs,
      trackCategory.orElse(prev.trackCategory),
      trackType.orElse(prev.trackType),
      departurePoint.orElse(prev.departurePoint),
      arrivalPoint.orElse(prev.arrivalPoint),
      etd.orElse(prev.etd),
      atd.orElse(prev.atd),
      eta.orElse(prev.eta),
      ata.orElse(prev.ata),
      route.orElse(prev.route)
    )
  }

  def accumulate (maybePref: Option[TrackInfo]): TrackInfo = {
    maybePref match {
      case Some(prev) => accumulate(prev)
      case None => this
    }
  }
}

//--- one time request/response (updates are handled through ChannelTopicRequests)
case class RequestTrackInfo(cs: String) // a one-time request for a given call sign
case class NoSuchTrackInfo(cs: String)

// our channel topic type (we keep this separate from one-time requests)
case class TrackInfoUpdateRequest(cs: String)

/**
  * a threadsafe store for TrackInfos
  */
trait TrackInfoStore {

  protected val trackInfos = TrieMap.empty[String,TrackInfo]

  def get (id: String): Option[TrackInfo] = trackInfos.get(id)
  def add (id: String, ti: TrackInfo) = trackInfos += (id -> ti)

  /**
    * to be provided by concrete store type if it supports dynamic updates
    *
    * @return true if input message was handled
    */
  def handleMessage(msg: Any): Boolean = false
}

trait ConfigurableTrackInfoStore extends TrackInfoStore with NamedConfigurable
