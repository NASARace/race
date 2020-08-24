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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonParseException, JsonSerializable, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.ListMap
import scala.concurrent.duration.Duration

/**
  * class representing the *description* of a provider (field owner)
  */
case class Provider (id: String, info: String, update: Duration) {

  def serializeTo (w: JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember("id", id)
      w.writeStringMember("info", info)
      w.writeLongMember("update", update.toMillis)
    }
  }
}


/**
  * class representing a versioned, named and ordered collection of Provider specs
  */
case class ProviderCatalog (id: String, info: String, date: DateTime, providers: ListMap[String,Provider]) extends JsonSerializable {

  def serializeTo (w: JsonWriter): Unit = {
    w.clear.writeObject(
      _.writeMemberObject("providerCatalog") {
        _.writeStringMember("id", id)
          .writeStringMember("info", info)
          .writeDateTimeMember("date", date)
          .writeArrayMember("providers") { w =>
            for (provider <- providers.valuesIterator) {
              provider.serializeTo(w)
            }
          }
      }
    )
  }

  def orderedEntries[T] (map: collection.Map[String,T]): Seq[(String,T)] =  {
    providers.foldLeft(Seq.empty[(String,T)]) { (acc,e) =>
      map.get(e._1) match {
        case Some(t) => acc :+ (e._1, t)
        case None => acc // provider not in map
      }
    }
  }
}

object ProviderCatalogParser {
  //--- lexical constants
  private val _date_ = asc("date")
  private val _providers_ = asc("providers")
  private val _id_ = asc("id")
  private val _info_ = asc("info")
  private val _update_ = asc("update")
}

/**
  * parser for provider specs
  */
class ProviderCatalogParser extends UTF8JsonPullParser {
  import ProviderCatalogParser._

  def parse (buf: Array[Byte]): Option[ProviderCatalog] = {
    initialize(buf)
    try {
      readNextObject {
        val catalogId = readQuotedMember(_id_).toString
        val catalogInfo = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        var providers = ListMap.empty[String,Provider]
        foreachInNextArrayMember(_providers_) {
          readNextObject {
            val id = readQuotedMember(_id_).toString
            val info = readQuotedMember(_info_).toString
            val update = readQuotedMember(_update_)
            val provider = Provider(id, info, update.toDuration)
            providers = providers + (id -> provider)
          }
        }
        Some(ProviderCatalog(catalogId,catalogInfo,date,providers))
      }

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerCatalog: ${x.getMessage}")
        None
    }
  }
}

