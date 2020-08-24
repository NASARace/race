/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.http.tabdata

import java.io.File

import com.typesafe.config.Config
import gov.nasa.race.common.JsonWriter
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{PublishingRaceActor, RaceContext, RaceException, SubscribingRaceActor}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import scala.collection.mutable

/**
  * the actor that collects/computes the model (data) and reports it to its data consumer RaceRouteInfo
  *
  * this should not be concerned about any RaceRouteInfo specifics (clients, data formats etc.), hence we assume
  * that all bus events are already translated
  */
class TabDataServiceActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  //--- the data

  val nodeId: String = config.getString("node-id")
  var fieldCatalog: FieldCatalog = readFieldCatalog
  var providerCatalog: ProviderCatalog = readProviderCatalog

  // readFrom and writeTo are used for DS/DR

  val upstreamId: Option[String] = config.getOptionalString("upstream-id") // who it is we are talking to
  val writeUpstreamTo: Option[String] = config.getOptionalString("write-upstream-to") // connection to the adapter (upstream NS/NR)
  val writeDownstreamTo: Option[String] = config.getOptionalString("write-downstream-to") // connection to own NS/NR
  val writer = new JsonWriter

  val providerData = readProviderData(fieldCatalog,providerCatalog)

  val updater: FieldValueUpdater = new PhasedInOrderUpdater(nodeId, fieldCatalog.fields) // TODO - should updater be configurable?
  updater.setLogging(info,warning,error)

  fieldCatalog.compileWithFunctions(FieldExpression.functions) // TODO - function library should be configurable
  initializeProviderData


  //--- actor interface

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    if (super.onInitializeRaceActor(raceContext,actorConf)) {
      val site = Node(nodeId, fieldCatalog, providerCatalog, upstreamId)

      writeUpstreamTo.foreach( publish(_,site))
      writeDownstreamTo.foreach( publish(_,site))

      publish(site)
      providerData.foreach(e => publish(e._2))
      true
    } else false
  }

  override def handleMessage: Receive = {
    case BusEvent(_, ss: NodeState, _) => handleUpstreamSiteState(ss)
    case BusEvent(_, pdc:ProviderDataChange, _) => updateProvider(pdc)
  }

  //--- data model init and update

  def readFieldCatalog: FieldCatalog = {
    val parser = new FieldCatalogParser
    parser.setLogging(info,warning,error)
    info("reading field catalog from " + config.getString("field-catalog"))
    config.translateFile("field-catalog")(parser.parse) match {
      case Some(fieldCatalog) => fieldCatalog
      case None => throw new RaceException("error parsing field-catalog")
    }
  }

  def readProviderCatalog: ProviderCatalog = {
    val parser = new ProviderCatalogParser
    parser.setLogging(info,warning,error)
    info("reading provider catalog from " + config.getString("provider-catalog"))
    config.translateFile("provider-catalog")(parser.parse) match {
      case Some(providerCatalog) => providerCatalog
      case None => throw new RaceException("error parsing provider-catalog")
    }
  }

  def readProviderData (fCatalog: FieldCatalog, pCatalog: ProviderCatalog): mutable.Map[String,ProviderData] = {
    val map = mutable.Map.empty[String,ProviderData]

    val dataDir = config.getExistingDir("provider-data")
    for (p <- pCatalog.providers) {
      val id = p._1
      val f = new File(dataDir, s"$id.json")
      info(s"reading provider data for '$id' from $f")
      if (f.isFile) {
        val parser = new ProviderDataParser(fCatalog)
        parser.setLogging(info,warning,error)
        parser.parse(FileUtils.fileContentsAsBytes(f).get) match {
          case Some(providerData) =>
            map += id -> providerData
          case None =>
            throw new RaceException(f"error parsing provider-data in $f")
        }
      } else {
        warning(s"no provider data for '$id'")
        map += id -> ProviderData(id,DateTime.now,pCatalog.id,fCatalog.id,Map.empty[String,FieldValue])
      }
    }

    map
  }

  def initializeProviderData: Unit = {
    providerData.foreach { pe=>
      val pd = pe._2
      val fv = pd.fieldValues
      val (_,newFv) = updater.initialize(fv, pd.date)

      if (fv ne newFv){
        val newPd = pd.copy(fieldValues=newFv)
        providerData.update(newPd.providerId, newPd) // we don't publish before start
      }
    }
  }

  def updateProvider (pdc: ProviderDataChange): Unit = {
    if (isValidChange(pdc)) {
      providerData.get(pdc.providerId) match {
        case Some(pd) =>
          if (pd.date < pdc.date) {
            val fv = pd.fieldValues
            val evalDate = if (pdc.changeNodeId == nodeId) pdc.date else DateTime.now
            val (evalChanges, newFv) = updater.update(fv, pdc, evalDate)

            if (fv ne newFv) { // did we change any FVs? If not we are done here

              //--- update pd and send out to local (device) clients
              val newPd = pd.copy(date = pdc.date, fieldValues = newFv)
              providerData.update(newPd.providerId, newPd)
              publish(newPd) // this is for local (device) clients

              distributeProviderDataChanges(pdc, evalChanges, evalDate) // send changes up- and downstream
            }
          }

        case None => // unknown provider
          warning(s"attempt to update unknown provider: ${pdc.providerId}")
      }
    }
  }

  /**
    * send changes to external sites (both upstream and downstream)
    */
  def distributeProviderDataChanges (pdc: ProviderDataChange, evalChanges: Seq[(String,FieldValue)], evalDate: DateTime): Unit = {

    def ownChanges (pdc: ProviderDataChange, evalChanges: Seq[(String,FieldValue)], evalDate: DateTime): ProviderDataChange = {
      pdc.copy(changeNodeId = nodeId, date = evalDate, fieldValues = evalChanges)
    }

    def coalesceChanges (pdc: ProviderDataChange, evalChanges: Seq[(String,FieldValue)], evalDate: DateTime): ProviderDataChange = {
      if (evalChanges.nonEmpty) {
        pdc.copy(changeNodeId = nodeId, date = evalDate, fieldValues = pdc.fieldValues ++ evalChanges)
      } else pdc
    }

    if (pdc.changeNodeId == nodeId) { // pdc originated locally, coalesce with eval changes and send in both directions
      val sendPdc = coalesceChanges(pdc,evalChanges,evalDate)
      writeUpstreamTo.foreach( publish(_,sendPdc))
      writeDownstreamTo.foreach( publish(_,sendPdc))

    } else { // change originated either upstream or downstream

      if (isUpstream(pdc.changeNodeId)) {
        if (evalChanges.nonEmpty) {
          // send eval changes upstream
          writeUpstreamTo.foreach(publish(_, ownChanges( pdc,evalChanges,evalDate)))
        }

        // send coalesced changes as own downstream
        writeDownstreamTo.foreach( publish(_, coalesceChanges( pdc,evalChanges,evalDate)))

      } else { // change originated downstream  - note our caller already verified pdc locations and date
        // send coalesced changes upstream
        writeUpstreamTo.foreach(publish(_, coalesceChanges(pdc, evalChanges, evalDate)))

        if (evalChanges.nonEmpty) {
          // note this came in through a ServerRoute, which already sent the original pdc to the non-originating providers
          // send only eval changes downstream
          writeDownstreamTo.foreach(publish(_, ownChanges(pdc, evalChanges, evalDate)))
        }
      }
    }
  }

  def isUpstream (id: String): Boolean = (upstreamId.isDefined && upstreamId.get == id)

  def isProvider (id: String): Boolean = providerCatalog.providers.contains(id)

  /**
    * is this from a valid site and are catalogs the same
    */
  def isValidChange (pdc: ProviderDataChange): Boolean = {
    ((pdc.changeNodeId == nodeId ) || isUpstream(pdc.changeNodeId) || isProvider(pdc.changeNodeId)) &&
      (pdc.providerCatalogId == providerCatalog.id && pdc.fieldCatalogId == fieldCatalog.id)
  }

  def handleUpstreamSiteState (ss: NodeState): Unit = {
    if (isUpstream(ss.siteId)) {
      info(s"processing site state from upstream '${ss.siteId}''")

      //--- send our own SS in return - this will get us data we don't have yet
      writeUpstreamTo.foreach( publish(_,new NodeState(nodeId, fieldCatalog, providerCatalog, providerData)))

      //--- send changes to our own PD which upstream does not have yet (we might have been operating disconnected)
      providerData.get(nodeId) match {
        case Some(ownPd) =>
          val upstreamDate = ss.providerDataDates.find( _._1 == nodeId) match {
            case Some((_, d)) => d
            case None => DateTime.Date0
          }
          ownPd.changesSince(upstreamDate,nodeId).foreach { pdc=>
            info(s"sending change of '${pdc.providerId}' in response to upstream site state of '${ss.siteId}''")
            writeUpstreamTo.foreach( publish(_,pdc))
          }

        case None => // we don't have our own data yet?
      }
    } else {
      warning(s"ignoring site state from non-upstream ${ss.siteId}")
    }
  }
}
