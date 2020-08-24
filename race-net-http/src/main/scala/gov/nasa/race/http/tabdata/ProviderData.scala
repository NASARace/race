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
import gov.nasa.race.common.JsonPullParser.{ObjectStart, UnQuotedValue}
import gov.nasa.race.common.{ConstAsciiSlice, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, MutAsciiSlice, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.{immutable, mutable}

/**
  * instance type of a provider that holds field values
  *
  * 'date' is the latest fieldValues change date
  *
  * note this does not have an explicit rev since we keep track of value change dates inside of FieldValues
  * (since changes can be initiated up- or down-stream rev numbers could collide without a sync protocol that
  * would introduce a single point of failure)
  */
case class ProviderData (providerId: String,
                         date: DateTime,
                         providerCatalogId: String,
                         fieldCatalogId: String,
                         fieldValues: immutable.Map[String,FieldValue]
                        ) extends JsonSerializable {

  private def _serializeTo (w: JsonWriter)(valueSerializer: JsonWriter=>Unit): Unit = {
    w.clear.writeObject( _
      .writeMemberObject("providerData") { _
        .writeStringMember("id", providerId)
        .writeDateTimeMember("date", date)
        .writeStringMember("providerCatalogId", providerCatalogId)
        .writeStringMember("fieldCatalogId", fieldCatalogId)
        .writeMemberObject("fieldValues") { w => valueSerializer(w) }
      }
    )
  }

  def serializeTo (w: JsonWriter): Unit = {
    _serializeTo(w){ w=>
      fieldValues.foreach { fv =>
        w.writeMemberName(fv._1)
        val wasFormatted = w.format(false) // print this dense no matter what
        fv._2.serializeTo(w)
        w.format(wasFormatted)
      }
    }
  }

  def serializeOrderedTo (w: JsonWriter, fields: Map[String,Field]): Unit = {
    _serializeTo(w) { w =>
      fields.foreach { e=>
        val id = e._1
        fieldValues.get(id) match {
          case Some(v) =>
            w.writeMemberName(id)
            val wasFormatted = w.format(false) // print this dense no matter what
            v.serializeTo(w)
            w.format(wasFormatted)
          case None => // ignore, we don't have a value for this field
        }
      }
    }
  }

  //--- change management operations

  def changesSince (refDate: DateTime, siteId: String): Option[ProviderDataChange] = {
    val changes = fieldValues.foldLeft(Seq.empty[(String,FieldValue)]) { (acc,e) =>
      val fv = e._2
      if (fv.date > refDate) acc :+ e else acc
    }
    if (changes.nonEmpty) {
      Some( ProviderDataChange(providerId,date,providerCatalogId,fieldCatalogId,siteId,changes) )
    } else None
  }

  def sameIdsAs (other: ProviderData): Boolean = {
    (providerId == other.providerId) && (providerCatalogId == other.providerCatalogId) && (fieldCatalogId == other.fieldCatalogId)
  }

  /**
    * this is a crucial function to guarantee convergence.
    *
    * it should obey the following principles:
    *   1. PDCs flow up, PDs down
    *   2. always process own/downstream PDCs first (make sure level is up-to-date)
    *   3. for each downstream/own change send (eval-extended) PDC upstream
    *   4.
    *
    *   - removing FVs has to be initiated upstream with a new FC
    */
  def mergeWithUpstreamPD (other: ProviderData, fields: Map[String,Field]): ProviderData = {
    if (!sameIdsAs(other)) return this // not a valid merge target

    val otherFVs = other.fieldValues
    var mergedFVs = fieldValues

    otherFVs.foreach { e =>
      val (id,otherFV) = e
      fieldValues.get(id) match {
        case Some(ownFv) =>
          if (otherFV.date > ownFv.date) { // other is newer -> overwrite own
            mergedFVs = mergedFVs + (id -> otherFV)
          }
          // TODO - should we check if same date but different value or just keep ours

        case None => // we don't have this fv - add if it is newer than our cut-off date
          if (otherFV.date > date) {
            mergedFVs = mergedFVs + (id -> otherFV)
          }
      }
    }

    // we don't delete FVs we have but upstream has not. If upstream wants to delete it has to modify the FC

    val newDate = if (date > other.date) other.date else date
    copy(date=newDate,fieldValues=mergedFVs)
  }
}


/**
  * parser for provider data
  *
  * TODO - this should have a map of FieldCatalogs as ctor args so that we can check against the right fields based on
  * what is specified in the PD
  */
class ProviderDataParser (fieldCatalog: FieldCatalog) extends UTF8JsonPullParser {
  private val _id_ = asc("id") // the provider id
  private val _date_ = asc("date")
  private val _providerCatalogId_ = asc("providerCatalogId")
  private val _fieldCatalogId_ = asc("fieldCatalogId")
  private val _fieldValues_ = asc("fieldValues")
  private val _value_ = asc("value")

  def parse (buf: Array[Byte]): Option[ProviderData] = {
    var latestChange = DateTime.Date0 // to hold the latest fieldValue change date (if all specified)
    val valueSlice = MutAsciiSlice.empty

    // we parse both explicit
    //     "<fieldId>": { "value": <num> [, "date": <epoch-ms>] }
    // and implicit
    //     "<fieldId>": <num>
    // if no date is specified we use the parseDate to instantiate FieldValues
    def readFieldValue (defaultDate: DateTime): Option[(String,FieldValue)] = {
      var fieldId: String = null
      var d: DateTime = DateTime.UndefinedDateTime

      readNext() match {
        case ObjectStart =>
          fieldId = member.toString
          valueSlice.setFrom(readUnQuotedMember(_value_))  // TODO - this should also handle arrays

          d = readOptionalDateTimeMember(_date_) match {
            case Some(date) =>
              if (date > latestChange) latestChange = date
              date
            case None => defaultDate
          }

          //ensureNextIsObjectEnd()
          skipPastAggregate()

        case UnQuotedValue =>
          fieldId = member.toString
          valueSlice.setFrom(value)
          d = defaultDate

        case _ => throw exception(s"invalid value for field '$member'")
      }

      fieldCatalog.fields.get(fieldId) match {
        case Some(field) => Some( (fieldId, field.valueFrom(valueSlice)(d)) )
        case None => warning(s"skipping unknown field $fieldId"); None
      }
    }

    initialize(buf)

    try {
      ensureNextIsObjectStart()
      val id = readQuotedMember(_id_).toString
      val date = readDateTimeMember(_date_)
      val providerCatalogId = readQuotedMember(_providerCatalogId_).toString
      val fieldCatalogId = readQuotedMember(_fieldCatalogId_).toString
      val fieldValues = readSomeNextObjectMemberInto[FieldValue,mutable.Map[String,FieldValue]](_fieldValues_,mutable.Map.empty){
        readFieldValue(date)
      }.toMap

      Some(ProviderData(id,date,providerCatalogId,fieldCatalogId,fieldValues))

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerData: ${x.getMessage}")
        None
    }
  }
}

object ProviderDataChange {
  // lexical constants for serialization/deserialization of ProviderDataChange objects
  val _providerDataChange_ : ConstAsciiSlice = asc("providerDataChange")
  val _providerId_ : ConstAsciiSlice = asc("providerId") // the provider id
  val _date_ : ConstAsciiSlice = asc("date")
  val _providerCatalogId_ : ConstAsciiSlice = asc("providerCatalogId")
  val _fieldCatalogId_ : ConstAsciiSlice = asc("fieldCatalogId")
  val _changeNodeId_ : ConstAsciiSlice = asc("changeNodeId")
  val _fieldValues_ : ConstAsciiSlice = asc("fieldValues")
  val _value_ : ConstAsciiSlice = asc("value")
}

/**
  * ProviderData change set
  *
  * 'date' is the change date for the associated fieldValues source, i.e. the source revision this change set is based on
  */
case class ProviderDataChange (providerId: String,
                               date: DateTime,
                               providerCatalogId: String,
                               fieldCatalogId: String,
                               changeNodeId: String,  // from which node we get the PDC
                               fieldValues: Seq[(String,FieldValue)]
                              ) extends JsonSerializable {
  import ProviderDataChange._

  /** order of fieldValues should not matter */
  def serializeTo (w: JsonWriter): Unit = {
    w.clear.writeObject { _
      .writeMemberObject(_providerDataChange_) { _
        .writeStringMember(_providerId_, providerId)
        .writeDateTimeMember(_date_, date)
        .writeStringMember(_providerCatalogId_, providerCatalogId)
        .writeStringMember(_fieldCatalogId_, fieldCatalogId)
        .writeStringMember(_changeNodeId_, changeNodeId)
        .writeMemberObject(_fieldValues_) { w =>
          fieldValues.foreach { fv =>
            w.writeMemberName(fv._1)
            val wasFormatted = w.format(false) // print this dense no matter what
            fv._2.serializeTo(w)  // this writes both value and date
            w.format(wasFormatted)
          }
        }
      }
    }
  }
}

/**
  * a parser for ProviderDataChange messages
  * note this is a trait so that we can compose parsers for specific message sets
  */
trait ProviderDataChangeParser extends JsonPullParser {
  import ProviderDataChange._

  val node: Node // to be provided by concrete type

  def parseProviderDataChange: Option[ProviderDataChange] = {
    readNextObjectMember(_providerDataChange_) {
      parseProviderDataChangeBody
    }
  }

  def parseProviderDataChangeBody: Option[ProviderDataChange] = {
    val valueSlice = MutAsciiSlice.empty

    def readFieldValue(): Option[(String,FieldValue)] = {
      val fieldId = readObjectMemberName().toString
      valueSlice.setFrom(readUnQuotedMember(_value_)) // TODO this should also handle arrays
      val date = readDateTimeMember(_date_)
      skipPastAggregate()

      node.fieldCatalog.fields.get(fieldId) match {
        case Some(field) =>
          Some(fieldId -> field.valueFrom(valueSlice)(date))
        case None =>
          warning(s"unknown field '$fieldId' in providerDataChange message ignored")
          None
      }
    }

    try {
      val id = readQuotedMember(_providerId_).toString
      val date = readDateTimeMember(_date_)
      val providerCatalogId = readQuotedMember(_providerCatalogId_).toString
      val fieldCatalogId = readQuotedMember(_fieldCatalogId_).toString
      val changeSiteId = readQuotedMember(_changeNodeId_).toString

      val fieldValues = readSomeNextObjectMemberInto[FieldValue,mutable.Buffer[(String,FieldValue)]](_fieldValues_,mutable.Buffer.empty){
        readFieldValue()
      }.toSeq

      Some(ProviderDataChange(id,date,providerCatalogId,fieldCatalogId,changeSiteId,fieldValues))

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerDataChange: ${x.getMessage}")
        None
    }
  }
}

