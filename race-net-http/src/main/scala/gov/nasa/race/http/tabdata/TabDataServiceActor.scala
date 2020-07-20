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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{DataConsumerRaceActor, RaceDataConsumer, RaceException}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import gov.nasa.race.config.ConfigUtils._

import scala.collection.mutable

/**
  * the actor that collects/computes the model (data) and reports it to its data consumer RaceRouteInfo
  * this should not be concerned about any RaceRouteInfo specifics (clients, data formats etc.)
  */
class TabDataServiceActor (_dc: RaceDataConsumer, _conf: Config) extends DataConsumerRaceActor(_dc,_conf) {

  //--- the data

  var fieldCatalog: FieldCatalog = readFieldCatalog
  var providerCatalog: ProviderCatalog = readProviderCatalog
  val providerData = readProviderData(fieldCatalog,providerCatalog)

  val updater: UpdatePolicy = new PhasedInOrderUpdater(fieldCatalog.fields) // TODO - should updater be configurable?
  updater.setLogging(info,warning,error)

  fieldCatalog.compileWithFunctions(FieldExpression.functions) // TODO - function library should be configurable
  initializeProviderData


  //--- actor interface

  override def onStartRaceActor(originator: ActorRef): Boolean = {

    // set the initial data
    setData(fieldCatalog)
    setData(providerCatalog)
    providerData.foreach(e=> setData(e._2))

    super.onStartRaceActor(originator)
  }

  override def handleMessage: PartialFunction[Any, Unit] = {
    case change:ProviderChange => // we get this directly from the routeinfo(s)
      updateProvider(change)

    case BusEvent(_, data: Any, _) => // nothing yet
    //setData(data)
  }

  //--- data model init and update

  def readFieldCatalog: FieldCatalog = {
    val parser = new FieldCatalogParser
    parser.setLogging(info,warning,error)
    config.translateFile("field-catalog")(parser.parse) match {
      case Some(fieldCatalog) => fieldCatalog
      case None => throw new RaceException("error parsing field-catalog")
    }
  }

  def readProviderCatalog: ProviderCatalog = {
    val parser = new ProviderCatalogParser
    parser.setLogging(info,warning,error)
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
        map += id -> ProviderData(id,0,DateTime.now,Map.empty[String,FieldValue])
      }
    }

    map
  }

  def initializeProviderData: Unit = {
    providerData.foreach { pe=>
      val pd = pe._2
      val fv = pd.fieldValues
      val fvʹ = updater.initialize(fv)

      if (fv ne fvʹ){
        val pdʹ = pd.copy(rev = pd.rev+1,fieldValues=fvʹ)
        providerData.update(pdʹ.id, pdʹ) // we don't setData() before start
      }
    }
  }

  def updateProvider (change: ProviderChange): Unit = {
    providerData.get(change.provider) match {
      case Some(pd) => // is it a provider we know
        val fv = pd.fieldValues
        val fvʹ = updater.update(fv,change.fieldValues)

        if (fv ne fvʹ){
          val pdʹ = pd.copy(rev = pd.rev+1,date=change.date,fieldValues=fvʹ)
          providerData.update(pdʹ.id, pdʹ)
          setData(pdʹ)
        }

      case None => // unknown provider
        warning(s"attempt to update unknown provider: ${change.provider}")
    }
  }
}
