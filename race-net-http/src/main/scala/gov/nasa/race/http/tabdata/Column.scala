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

import java.nio.file.Path
import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{Glob, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, TimeTrigger, UTF8JsonPullParser, UnixPath}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

object Column {
  //--- lexical constants
  val _id_        = asc("id")
  val _info_      = asc("info")
  val _node_      = asc("node")
  val _attrs_     = asc("attrs")
  val _check_     = asc("check")

  def apply (id: String): Column = new Column(id,s"this is column $id", UpdateFilter.sendUpReceiveLocalUp, id, Seq.empty[String], None)
}


/**
  * class representing the *description* of a provider (field owner)
  */
case class Column (id: String, info: String, updateFilter: UpdateFilter, node: String, attrs: Seq[String], check: Option[TimeTrigger])
              extends JsonSerializable {
  import Column._

  def serializeTo (w: JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember(_id_, id)
      w.writeStringMember(_info_, info)
      updateFilter.serializeTo(w)
      if (node != id) w.writeStringMember(_node_, node)
      if (attrs.nonEmpty) w.writeStringArrayMember(_attrs_, attrs)
      check.foreach( tt=> w.writeStringMember(_check_, tt.toSpecString))
    }
  }
}


trait ColumnParser extends JsonPullParser with UpdateFilterParser with AttrsParser {
  import Column._

  def parseColumn (listId: String): Column = {
    val id = PathIdentifier.resolve(readQuotedMember(_id_),listId)
    val info = readQuotedMember(_info_).toString
    val updateFilter = parseUpdateFilter(listId)
    val nodeId = readOptionalQuotedMember(_node_) match {
      case Some(ps) => PathIdentifier.resolve(ps, listId)
      case None => id
    }
    val attrs = readAttrs (_attrs_)
    // this is either a duration in ISO spec or a time-of-day in HH:mm:SS
    val check = readOptionalQuotedMember(_check_).map(TimeTrigger.apply)

    Column(id, info, updateFilter, nodeId, attrs, check)
  }
}

object ColumnList {
  //--- lexical constants
  val _columnList_ = asc("columnList")
  val _date_ = asc("date")
  val _columns_ = asc("columns")
  val _id_ = asc("id")
  val _info_ = asc("info")

  def apply (id: String, date: DateTime, columns: Column*): ColumnList = {
    val cols = columns.foldLeft(ListMap.empty[String,Column])( (acc,col) => acc + (col.id -> col))
    new ColumnList(id, s"this is column list $id", date, cols)
  }
}

/**
  * class representing a versioned, named and ordered collection of Column specs
  */
case class ColumnList (id: String, info: String, date: DateTime, columns: ListMap[String,Column]) extends JsonSerializable {
  import ColumnList._

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject(
      _.writeMemberObject(_columnList_) {
        _.writeStringMember(_id_, id.toString)
          .writeStringMember(_info_, info)
          .writeDateTimeMember(_date_, date)
          .writeArrayMember(_columns_) { w =>
            for (col <- columns.valuesIterator) {
              col.serializeTo(w)
            }
          }
      }
    )
  }

  //--- (some) list/map forwarders
  def size: Int = columns.size
  def apply (p: String): Column = columns(p)
  def get (p: String): Option[Column] = columns.get(p)
  def contains (p: String): Boolean = columns.contains(p)
  def foreach[U] (f: (Column)=>Unit): Unit = columns.foreach( e=> f(e._2))

  def orderedEntries[T] (map: collection.Map[String,T]): Seq[(String,T)] =  {
    columns.foldRight(Seq.empty[(String,T)]) { (e,acc) =>
      map.get(e._1) match {
        case Some(t) => (e._1, t) +: acc
        case None => acc // provider not in map
      }
    }
  }

  def filteredEntries[T] (map: collection.Map[String,T])(f: Column=>Boolean): Seq[(String,T)] =  {
    columns.foldLeft(Seq.empty[(String,T)]) { (acc, e) =>
      if (f(e._2)) {
        map.get(e._1) match {
          case Some(t) => acc :+ (e._1, t)
          case None => acc // provider not in map
        }
      } else acc
    }
  }

  def matching (globPattern: String): Iterable[Column] = {
    val regex = Glob.resolvedGlob2Regex(globPattern, id)
    columns.foldRight(Seq.empty[Column]){ (e,list) => if (regex.matches(e._1)) e._2 +: list else list }
  }
}

/**
  * parser for provider specs
  */
class ColumnListParser extends UTF8JsonPullParser with ColumnParser {
  import ColumnList._

  def parse (buf: Array[Byte]): Option[ColumnList] = {
    initialize(buf)
    try {
      readNextObject {
        val id = readQuotedMember(_id_).toString
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        var columns = ListMap.empty[String,Column]
        foreachInNextArrayMember(_columns_) {
          readNextObject {
            val col = parseColumn(id)
            columns = columns + (col.id -> col)
          }
        }
        Some(ColumnList(id,info,date,columns))
      }

    } catch {
      case x: JsonParseException =>
        warning(s"malformed providerCatalog: ${x.getMessage}")
        None
    }
  }
}

