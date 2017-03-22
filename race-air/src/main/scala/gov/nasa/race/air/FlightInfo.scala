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
package gov.nasa.race.air

import gov.nasa.race._
import gov.nasa.race.util.{XmlAttrProcessor, XmlPullParser}
import org.joda.time.DateTime

import scala.collection.concurrent.TrieMap

/**
  * flight plan and other non-positional info
  *
  * note we don't keep this as a case class since we want to be able to extend it
  * all but the identifiers are optional since they can trickle in over some time
  *
  * TODO - the basic version should probably also include source, assigned flight level,
  * route and next event, but we need a general extension mechanism anyways
  */
class FlightInfo (val flightRef: String,
                  val cs: String,
                  val acCat: Option[String],
                  val acType: Option[String],
                  val departurePoint: Option[String],
                  val arrivalPoint: Option[String],
                  val etd: Option[DateTime],
                  val atd: Option[DateTime],
                  val eta: Option[DateTime],
                  val ata: Option[DateTime]) {

  override def toString = { // compact version
    val sb = new StringBuilder
    sb.append(s"FlightInfo{cs:$cs")
    ifSome(acType){ s=> sb.append(s",type:$s")}
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

//--- one time request/response (updates are handled through ChannelTopicRequests)
case class RequestFlightInfo(cs: String) // a one-time request for a given call sign
case class NoSuchFlightInfo(cs: String)

// our channel topic type (we keep this separate from one-time requests)
case class FlightInfoUpdateRequest(cs: String)

/**
  * a store for FlightInfos that are updated from tfmDataService (SWIM) messages
  * this abstracts the actual storage type and update method
  *
  * TODO -
  */
class FlightInfoStore {

  val flightInfos = TrieMap.empty[String,FlightInfo]

  def updateFrom (src: FlightInfoSource) = {
    flightInfos.get(src.cs) match {
      case Some(info) => // don't change if you don't have to
        val newInfo = src.amendFlightInfo(info)
        if (newInfo ne info) update(info.cs,  newInfo)
      case None => update(src.cs, src.createFlightInfo)
    }
  }

  // can be overridden to inform listeners etc.
  protected def update (key: String, fInfo: FlightInfo): Unit = flightInfos += (key -> fInfo)

  def get (cs: String) = flightInfos.get(cs)
}

/**
  * a generic source for FlightInfo updates
  */
trait FlightInfoSource {

  var flightRef: String = null
  var cs: String = null
  var acCat, acType: String = null
  var departurePoint, arrivalPoint: String = null
  var etd, atd, eta, ata: DateTime = null

  def resetVars = {
    flightRef = null;
    cs = null;
    acCat = null;
    acType = null
    departurePoint = null;
    arrivalPoint = null
    etd = null;
    atd = null;
    eta = null;
    ata = null
  }

  def createFlightInfo = new FlightInfo(
    flightRef, cs,
    Option(acCat), Option(acType),
    Option(departurePoint), Option(arrivalPoint),
    Option(etd), Option(atd), Option(eta), Option(ata)
  )

  /** only create a new object if there are changes */
  def amendFlightInfo(fi: FlightInfo): FlightInfo = {
    if ((acCat != fi.acCat.orNull) || (acType != fi.acType.orNull) ||
        (departurePoint != fi.departurePoint.orNull) || (arrivalPoint != fi.arrivalPoint.orNull) ||
        (etd != fi.etd.orNull) || (atd != fi.atd.orNull) ||
        (eta != fi.eta.orNull) || (ata != fi.ata.orNull)) {
      new FlightInfo(
        fi.flightRef, fi.cs,
        if (acCat != null) Some(acCat) else fi.acCat,
        if (acType != null) Some(acType) else fi.acType,
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

class FlightInfoTfmParser (store: FlightInfoStore)
            extends XmlPullParser with XmlAttrProcessor with FlightInfoSource {
  setBuffered(4096)

  def parse (tfmDataMsg: String) = {
    initialize(tfmDataMsg)

    def parseTimeValueAttr (typeAttr: String, typeVal: String,
                            equalAction: (DateTime)=>Unit, notEqualAction: (DateTime)=>Unit) = {
      var tv: String = null
      var date: DateTime = null
      processAttributes {
        case `typeAttr` => tv = value
        case "timeValue" => if (value != null) date = DateTime.parse(value)
      }
      if (tv == typeVal) equalAction(date) else notEqualAction(date)
    }

    try {
      while (parseNextElement()) {
        if (isStartElement) {
          tag match {
            case "fdm:fltdMessage" => // this starts a new entry
              resetVars
              flightRef = readAttribute("flightRef")
            case "nxce:aircraftId" =>
              cs = trimmedTextOrNull()
            case "nxce:airport" =>
              if (hasParent("nxce:departurePoint")) departurePoint = trimmedTextOrNull()
              else if (hasParent("nxce:arrivalPoint")) arrivalPoint = trimmedTextOrNull()
            case "nxcm:etd" => // ? "actual" estimated time ?
              parseTimeValueAttr("etdType", "ACTUAL", atd_=, etd_=)
            case "nxcm:eta" =>
              parseTimeValueAttr("etaType", "ESTIMATED", eta_=, ata_=)
            case "nxcm:newFlightAircraftSpecs" =>
              acCat = attributeOrNull("specialAircraftQualifier")
              acType = trimmedTextOrNull()
            case other => // ignore - this could be our extension point
          }

        } else {  // end element
          tag match {
            case "fdm:fltdMessage" => store.updateFrom(this)
            case other => // ignore
          }
        }
      }
    } catch {
      case t: Throwable => t.printStackTrace()
    }
  }
}