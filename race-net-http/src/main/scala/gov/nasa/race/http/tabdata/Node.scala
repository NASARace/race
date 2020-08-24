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
import gov.nasa.race.common.{ConstAsciiSlice, JsonPullParser, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable

/**
  * the mostly static data for a site, for internal distribution
  * this is the minimum data each client / server needs
  */
case class Node(id: String,
                fieldCatalog: FieldCatalog,
                providerCatalog: ProviderCatalog,
                upstreamId: Option[String]
                ) {

  def isKnownProvider (id: String): Boolean = providerCatalog.providers.contains(id)
  def isKnownField (id: String): Boolean = fieldCatalog.fields.contains(id)

  def isProviderCatalogUpToDate (ss: NodeState): Boolean = {
    providerCatalog.id == ss.providerCatalogId && providerCatalog.date == ss.providerCatalogDate
  }

  def isFieldCatalogUpToDate (ss: NodeState): Boolean = {
    fieldCatalog.id == ss.fieldCatalogId && fieldCatalog.date == ss.fieldCatalogDate
  }

  def isLocal (testId: String): Boolean = id == testId
  def isUpstream (testId: String): Boolean = upstreamId.isDefined && upstreamId.get == testId
}


object NodeState {
  //--- lexical constants
  val _nodeState_ : ConstAsciiSlice = asc("siteState")
  val _nodeId_ : ConstAsciiSlice = asc("id")
  val _fieldCatalogId_ : ConstAsciiSlice = asc("fieldCatalogId")
  val _fieldCatalogDate_ : ConstAsciiSlice = asc("fieldCatalogDate")
  val _providerCatalogId_ : ConstAsciiSlice = asc("providerCatalogId")
  val _providerCatalogDate_ : ConstAsciiSlice = asc("providerCatalogDate")
  val _providerDataDates_ : ConstAsciiSlice = asc("providerDataDates")
}

/**
  * site specific snapshot of local catalog and provider data dates (last modifications)
  */
case class NodeState(siteId: String,
                     fieldCatalogId: String, fieldCatalogDate: DateTime,
                     providerCatalogId: String, providerCatalogDate: DateTime,
                     providerDataDates: Seq[(String,DateTime)]
                     ) extends JsonSerializable {
  import NodeState._

  def this (nodeId: String, fieldCatalog: FieldCatalog, providerCatalog: ProviderCatalog, providerData: collection.Map[String,ProviderData]) = this(
    nodeId,
    fieldCatalog.id,fieldCatalog.date,
    providerCatalog.id,providerCatalog.date,
    providerCatalog.orderedEntries(providerData).map(e=> (e._1,e._2.date))
  )

  def this (node: Node, providerData: collection.Map[String,ProviderData]) = this(
    node.id, node.fieldCatalog, node.providerCatalog, providerData
  )


  def serializeTo (w: JsonWriter): Unit = {
    w.clear.writeObject {_
      .writeMemberObject(_nodeState_) {_
        .writeStringMember(_nodeId_, siteId)
        .writeStringMember(_fieldCatalogId_, fieldCatalogId)
        .writeDateTimeMember(_fieldCatalogDate_, fieldCatalogDate)
        .writeStringMember(_providerCatalogId_, providerCatalogId)
        .writeDateTimeMember(_providerCatalogDate_, providerCatalogDate)

        .writeMemberObject(_providerDataDates_) { w =>
          providerDataDates.foreach { e =>
            w.writeDateTimeMember(e._1, e._2)
          }
        }
      }
    }
  }
}

/**
  * parser mixin for SiteState
  */
trait NodeStateParser extends JsonPullParser {
  import NodeState._

  def parseSiteState: Option[NodeState] = {
    readNextObjectMember(_nodeState_) {
      parseSiteStateBody
    }
  }

  def parseSiteStateBody: Option[NodeState] = {
    val siteId = readQuotedMember(_nodeId_).toString
    val fieldCatalogId = readQuotedMember(_fieldCatalogId_).toString
    val fieldCatalogDate = readDateTimeMember(_fieldCatalogDate_)
    val providerCatalogId = readQuotedMember(_providerCatalogId_).toString
    val providerCatalogDate = readDateTimeMember(_providerCatalogDate_)

    val providerDataDates = readSomeNextObjectMemberInto[DateTime, mutable.Buffer[(String, DateTime)]](_providerDataDates_, mutable.Buffer.empty) {
      val providerDate = readDateTime()
      val providerId = member.toString
      Some( (providerId -> providerDate) )
    }.toSeq

    Some(NodeState(siteId, fieldCatalogId, fieldCatalogDate, providerCatalogId, providerCatalogDate, providerDataDates))
  }

}
