package gov.nasa.race.track

import gov.nasa.race.ifSome
import org.joda.time.DateTime

import scala.collection.concurrent.TrieMap

/**
  * non-positional info for tracks, such as departure and arrival location and times, flight plans etc.
  * This is supposed to be an accumulator that is updated from different channels to collect all information
  * about a certain track, hence it depends on inter-channel ids ('cs')
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
                val ata: Option[DateTime]) {

  override def toString = { // compact version
    val sb = new StringBuilder
    sb.append(s"TrackInfo{cs:$cs")
    ifSome(trackType){ s=> sb.append(s",type:$s")}
    ifSome(departurePoint){ s=> sb.append(s",from:$s")}
    ifSome(arrivalPoint){ s=> sb.append(s",to:$s")}
    if (atd.isDefined) sb.append(s",atd:${atd.get}")
    else if (etd.isDefined) sb.append(s",etd:${etd.get}")
    if (ata.isDefined) sb.append(s",ata:${ata.get}")
    else if (eta.isDefined) sb.append(s",eta:${eta.get}")
    sb.append('}')
    sb.toString
  }
}


/**
  * a generic source for TrackInfo updates
  */
trait TrackInfoSource {

  var trackRef: String = null
  var cs: String = null
  var trackCat, trackType: String = null
  var departurePoint, arrivalPoint: String = null
  var etd, atd, eta, ata: DateTime = null

  def resetVars = {
    trackRef = null;
    cs = null;
    trackCat = null;
    trackType = null
    departurePoint = null;
    arrivalPoint = null
    etd = null;
    atd = null;
    eta = null;
    ata = null
  }

  def createTrackInfo = new TrackInfo(
    trackRef, cs,
    Option(trackCat), Option(trackType),
    Option(departurePoint), Option(arrivalPoint),
    Option(etd), Option(atd), Option(eta), Option(ata)
  )

  /** only create a new object if there are changes */
  def amendTrackInfo(fi: TrackInfo): TrackInfo = {
    if ((trackCat != fi.trackCategory.orNull) || (trackType != fi.trackType.orNull) ||
      (departurePoint != fi.departurePoint.orNull) || (arrivalPoint != fi.arrivalPoint.orNull) ||
      (etd != fi.etd.orNull) || (atd != fi.atd.orNull) ||
      (eta != fi.eta.orNull) || (ata != fi.ata.orNull)) {
      new TrackInfo(
        fi.trackRef, fi.cs,
        if (trackCat != null) Some(trackCat) else fi.trackCategory,
        if (trackType != null) Some(trackType) else fi.trackType,
        if (departurePoint != null) Some(departurePoint) else fi.departurePoint,
        if (arrivalPoint != null) Some(arrivalPoint) else fi.arrivalPoint,
        if (etd != null) Some(etd) else fi.etd,
        if (atd != null) Some(atd) else fi.atd,
        if (eta != null) Some(eta) else fi.eta,
        if (ata != null) Some(ata) else fi.ata
      )
    } else fi // no change
  }
}

//--- one time request/response (updates are handled through ChannelTopicRequests)
case class RequestTrackInfo(cs: String) // a one-time request for a given call sign
case class NoSuchTrackInfo(cs: String)

// our channel topic type (we keep this separate from one-time requests)
case class TrackInfoUpdateRequest(cs: String)

/**
  * a (possibly global) store for TrackInfos that are updated from (potentially)
  * multiple sources
  */
class TrackInfoStore {

  val trackInfos = TrieMap.empty[String,TrackInfo]

  def updateFrom (src: TrackInfoSource) = {
    trackInfos.get(src.cs) match {
      case Some(info) => // don't change if you don't have to
        val newInfo = src.amendTrackInfo(info)
        if (newInfo ne info) update(info.cs,  newInfo)
      case None => update(src.cs, src.createTrackInfo)
    }
  }

  // can be overridden to inform listeners etc.
  protected def update (key: String, fInfo: TrackInfo): Unit = trackInfos += (key -> fInfo)

  def get (cs: String) = trackInfos.get(cs)
}
