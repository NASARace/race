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

package gov.nasa.race.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{CharSeqByteSlice, Glob, JsonMessageObject, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.share.NodeMatcher.{noneMatcher, selfMatcher}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.{ListMap, SeqMap}

object Column extends JsonConstants {
  //--- lexical constants
  val OWNER = asc("owner")

  def apply (id: String): Column = new Column(id,s"this is column $id", id,  noneMatcher, selfMatcher, Seq.empty[String])
}


/**
  * class representing a data provider (field owner). Note that each column is uniquely associated to one node, but
  * nodes can own several columns
  *
  * note that owner can be an abstract name (<up>,<self>,..) which has to be resolved through a Node object
  */
case class Column (id: String, info: String, owner: String, send: NodeMatcher, receive: NodeMatcher, attrs: Seq[String])
              extends JsonSerializable {
  import Column._

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeStringMember(ID, id)
    w.writeStringMember(INFO, info)
    w.writeStringMember(OWNER,owner)
    w.writeStringMember(RECEIVE, receive.pattern)
    w.writeStringMember(SEND, send.pattern)
    if (attrs.nonEmpty) w.writeStringArrayMember(ATTRS, attrs)
  }

  /**
    * serialize without send/receive filters, only id, info and owner
    */
  def shortSerializeTo (w: JsonWriter): Unit = {
    w.writeObject { w=>
      w.writeStringMember(ID, id)
      w.writeStringMember(INFO, info)
      w.writeStringMember(OWNER,owner)
      if (attrs.nonEmpty) w.writeStringArrayMember(ATTRS, attrs)
    }
  }

  def isSentTo (nodeId: String)(implicit node: Node): Boolean = send.matches(nodeId)(node,Some(owner))
  def isReceivedFrom (nodeId: String)(implicit node: Node): Boolean = receive.matches(nodeId)(node,Some(owner))

  def isOnlyReceivedFromUpstream: Boolean = receive eq NodeMatcher.upMatcher
}


trait ColumnParser extends JsonPullParser with NodeMatcherParser  with AttrsParser {
  import Column._

  def readColumn (nodeId: String): Column = {
    var id: String = null
    var info: String = ""
    var send: NodeMatcher = noneMatcher
    var receive: NodeMatcher = noneMatcher
    var owner: String = null
    var attrs = Seq.empty[String]

    foreachMemberInCurrentObject {
      case ID => id = quotedValue.intern
      case INFO => info = quotedValue.toString
      case SEND => send = readNodeMatcher(quotedValue,nodeId)
      case RECEIVE => receive = readNodeMatcher(quotedValue,nodeId)
      case OWNER => // preserve abstract names
        val ownerSpec = quotedValue.toString
        owner = if (ownerSpec.startsWith("<")) ownerSpec else PathIdentifier.resolve(quotedValue.toString, nodeId)
      case ATTRS => attrs = readCurrentArray( readAttrs() )
    }

    if (id == null) throw exception("missing 'id' in column spec")
    if (owner == null) owner = id

    Column(id,info,owner,send,receive,attrs)
  }
}

object ColumnList extends JsonConstants {
  //--- lexical constants
  val COLUMN_LIST = asc("columnList")

  def apply (id: String, date: DateTime, columns: Column*): ColumnList = {
    val cols = columns.foldLeft(ListMap.empty[String,Column])( (acc,col) => acc + (col.id -> col))
    new ColumnList(id, s"this is column list $id", date, cols)
  }
}

/**
  * class representing a versioned, named and ordered collection of Column specs
  */
case class ColumnList (id: String, info: String, date: DateTime, columns: SeqMap[String,Column]) extends JsonMessageObject {
  import ColumnList._

  def _serializeMembersTo (w: JsonWriter)(serializeColumn: (Column,JsonWriter)=>Unit): Unit = {
    w.writeObjectMember(COLUMN_LIST) {
      _.writeStringMember(ID, id.toString)
        .writeStringMember(INFO, info)
        .writeDateTimeMember(DATE, date)
        .writeArrayMember(COLUMNS) { w =>
          for (col <- columns.valuesIterator) {
            serializeColumn(col,w)
          }
        }
    }
  }

  def serializeMembersTo (w: JsonWriter): Unit = _serializeMembersTo(w)( (col,w) => col.serializeTo(w))
  def shortSerializeTo (w: JsonWriter): Unit = {
    w.clear()
    w.writeObject(w=> _serializeMembersTo(w)( (col,w) => col.shortSerializeTo(w)))
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
  * parser for column specs
  */
class ColumnListParser (nodeId: String) extends UTF8JsonPullParser with ColumnParser {
  import ColumnList._

  def readColumns(): SeqMap[String,Column] = {
    var map = ListMap.empty[String,Column]
    foreachElementInCurrentArray {
      val col = readColumn(nodeId)
      map = map + (col.id -> col)
    }
    map
  }

  def readColumnList(): ColumnList = {
    readCurrentObject {
      var id: String = null
      var info: String = null
      var date: DateTime = DateTime.UndefinedDateTime
      var columns = SeqMap.empty[String,Column]

      foreachMemberInCurrentObject {
        case ID => id = quotedValue.toString
        case INFO => info = quotedValue.toString
        case DATE => date = dateTimeValue
        case COLUMNS => columns = readCurrentArray( readColumns() )
      }

      if (id == null) throw exception("missing 'id' in columnList")
      ColumnList(id,info,date,columns)
    }
  }

  def parse (buf: Array[Byte]): Option[ColumnList] = {
    initialize(buf)
    try {
      readNextObject {
        Some( readNextObjectMember(COLUMN_LIST){ readColumnList() } )
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed columnList: ${x.getMessage}")
        None
    }
  }
}


/**
  * columnReachabilityChange: { id: <id>, date: <epoch>, isOnline: <bool>, columns: [ <id>...] }
  */

object ColumnReachabilityChange {
  val COL_REACHABILITY_CHANGE = asc("columnReachabilityChange")
  val NODE_ID = asc("id")
  val DATE = asc("date")
  val IS_ONLINE = asc("isOnline")
  val COLUMNS = asc("columns")

  def online (nodeId: String, date: DateTime, newOnlines: Seq[String]) = ColumnReachabilityChange(nodeId,date,true,newOnlines)
  def offline (nodeId: String, date: DateTime, newOfflines: Seq[String]) = ColumnReachabilityChange(nodeId,date,false,newOfflines)
}


/**
  * indicates bulk change of column isOnline status of the respective node
  */
case class ColumnReachabilityChange (nodeId: String, // the node that reports the change
                                     date: DateTime, // the date when the change happened (simTime)
                                     isOnline: Boolean, // do columns become online or offline
                                     columns: Seq[String] // the columns that change reachability
                                    ) extends JsonMessageObject {
  import gov.nasa.race.share.ColumnReachabilityChange._

  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeObjectMember(COL_REACHABILITY_CHANGE) { _
      .writeStringMember(NODE_ID, nodeId)
      .writeDateTimeMember(DATE, date)
      .writeBooleanMember(IS_ONLINE, isOnline)
      .writeStringArrayMember(COLUMNS, columns)
    }
  }
}
