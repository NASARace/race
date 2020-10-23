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
import gov.nasa.race.common.{JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, TimeTrigger, UTF8JsonPullParser, UnixPath}
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
}


/**
  * class representing the *description* of a provider (field owner)
  */
case class Column (id: Path, info: String, updateFilter: UpdateFilter, node: Path, attrs: Seq[String], check: Option[TimeTrigger])
              extends PathObject with JsonSerializable {
  import Column._

  def this (id: Path) = this(id,s"this is column $id", UpdateFilter.defaultFilter, id, Seq.empty[String], None)

  private def _serializeTo (w: JsonWriter, listId: Path): Unit = {
    w.writeObject { w=>
      w.writeStringMember(_id_, if (listId != null) listId.relativize(id).toString else id.toString)
      w.writeStringMember(_info_, info)
      updateFilter.serializeTo(w)
      if (node != id) w.writeStringMember(_node_, if (listId != null) listId.relativize(node).toString else node.toString)
      if (attrs.nonEmpty) w.writeStringArrayMember(_attrs_, attrs)
      check.foreach( tt=> w.writeStringMember(_check_, tt.toSpecString))
    }
  }

  def serializeTo (w: JsonWriter) = _serializeTo(w,null)
  def serializeRelativeTo (w: JsonWriter, parentPath: Path) = _serializeTo(w,parentPath)

  def resolve (p: Path): Path = id.resolve(p)
}


trait ColumnParser extends JsonPullParser with UpdateFilterParser with AttrsParser {
  import Column._

  def parseColumn (listId: Path): Column = {
    val id = listId.resolve(UnixPath.intern(readQuotedMember(_id_))).normalize
    val info = readQuotedMember(_info_).toString
    val updateFilter = parseUpdateFilter(listId)
    val nodeId = readOptionalQuotedMember(_node_) match {
      case Some(ps) => listId.resolve(UnixPath.intern(ps)).normalize
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
}

/**
  * class representing a versioned, named and ordered collection of Column specs
  */
case class ColumnList (id: Path, info: String, date: DateTime, columns: ListMap[Path,Column]) extends JsonSerializable {
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
  def apply (p: Path): Column = columns(p)
  def get (p: Path): Option[Column] = columns.get(p)
  def contains (p: Path): Boolean = columns.contains(p)
  def foreach[U] (f: ((Path,Column))=>U): Unit = columns.foreach(f)

  def orderedEntries[T] (map: collection.Map[Path,T]): Seq[(Path,T)] =  {
    columns.foldLeft(Seq.empty[(Path,T)]) { (acc, e) =>
      map.get(e._1) match {
        case Some(t) => acc :+ (e._1, t)
        case None => acc // provider not in map
      }
    }
  }

  def getMatchingColumns (nodePath: Path, colSpec: String): Seq[Column] = {
    if (UnixPath.isCurrent(colSpec)) {
      columns.get(nodePath).toList
    } else {
      if (UnixPath.isPattern(colSpec)) {
        val pm = UnixPath.matcher(colSpec)
        columns.foldLeft(ArrayBuffer.empty[Column]){ (acc,e) =>
          if (pm.matches(e._1)) acc += e._2 else acc
        }.toSeq

      } else {
        val p = nodePath.resolve(UnixPath(colSpec))
        columns.get(p).toList
      }
    }
  }

  def resolvePathSpec (colSpec: String): String = {
    if (UnixPath.isAbsolutePathSpec(colSpec)) {
      colSpec
    } else {
      id.toString + '/' + colSpec
    }
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
        val id = UnixPath.intern(readQuotedMember(_id_))
        val info = readQuotedMember(_info_).toString
        val date = readDateTimeMember(_date_)

        var columns = ListMap.empty[Path,Column]
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

