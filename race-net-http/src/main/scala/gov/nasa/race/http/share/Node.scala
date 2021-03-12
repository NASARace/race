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
package gov.nasa.race.http.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{Clock, ConstAsciiSlice, InetAddressMask, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.StringUtils

import scala.collection.immutable.{ListMap, SeqMap}
import scala.collection.mutable.ArrayBuffer

/**
  * the data model for each node, consisting of invariant (start-time) data such as the column/rowList(s) and the
  * variable columnData for each of the columnList entries
  *
  * Node objects are only supposed to be used process-internal as input for other local actors, i.e. we do not serialize
  * them (hence they can include non-serializable data)
  *
  * note that Node objects are invariant so that they can be passed around between actors. Mutators for columnData
  * therefore have to return new Node objects
  *
  * we keep all the data in one structure so that actors do not have to handle transient (partial) initialization states
  */
case class Node ( upstreamId: Option[String],  // the currently selected upstream (there can only be one)

                  //--- semi-static config lists
                  nodeList: NodeList,
                  columnList: ColumnList,
                  rowList: RowList,

                  //--- variable data
                  columnDatas: Map[String,ColumnData],

                  //--- variable non-data state
                  clock: Clock = Clock.wallClock,
                  violatedConstraints: Set[ConstraintFormula] = Set.empty,
                  onlineColumns: Set[String] = Set.empty
                ) {

  val id = nodeList.self.id
  // upstreamId is going to be selected

  def currentDateTime: DateTime = clock.dateTime

  def isKnownNode (nid: String): Boolean = nodeList.isKnownNode(nid)
  def isKnownDownstreamNode (nid: String): Boolean = nodeList.downstreamNodes.contains(nid)

  def isKnownColumn(cid: String): Boolean = columnList.columns.contains(cid)
  def isKnownRow(rid: String): Boolean = rowList.rows.contains(rid)

  def isOwnNode (nid: CharSequence): Boolean = id == nid
  def isUpstreamNode (nid: CharSequence): Boolean = upstreamId.isDefined && upstreamId.get == nid

  def cellValue (colId: String, rowId: String): CellValue[_] = columnDatas(colId)(rowList(rowId))

  def apply (colId: String): ColumnData = columnDatas(colId)
  def get (colId: String): Option[ColumnData] = columnDatas.get(colId)
  def get (colId: String, rowId: String): Option[CellValue[_]] = get(colId).flatMap( _.get(rowId))

  def getEvalContext (date: DateTime = DateTime.UndefinedDateTime): EvalContext = new BasicEvalContext(id,rowList,date,columnDatas)

  def update (cd: ColumnData): Node = copy(columnDatas= columnDatas + (cd.id -> cd))

  //--- tracking constraint violations

  def hasConstraintViolations: Boolean = violatedConstraints.nonEmpty

  def hasConstraintViolation (cf: ConstraintFormula): Boolean = violatedConstraints.contains(cf)

  def getViolatedConstraints (colId: String, rowId: String): Seq[ConstraintFormula] = {
    violatedConstraints.foldLeft(Seq.empty[ConstraintFormula]) { (acc,cf) =>
      if (cf.assoc.exists( cr=> cr.colId == colId && cr.rowId == rowId)) cf +: acc else acc
    }.sortWith( (a,b) => a.level > b.level)
  }

  def setConstraintViolation (cf: ConstraintFormula): Node = {
    if (violatedConstraints.contains(cf)) this else copy(violatedConstraints= violatedConstraints + cf)
  }

  def resetConstraintViolation (cf: ConstraintFormula): Node = {
    if (violatedConstraints.contains(cf)) copy(violatedConstraints= violatedConstraints - cf) else this
  }

  def foreachConstraintViolation (action: ConstraintFormula=>Unit): Unit = violatedConstraints.foreach(action)

  def currentConstraintViolations: ConstraintChange = {
    ConstraintChange(currentDateTime, true, violatedConstraints.toSeq, Seq.empty[ConstraintFormula])
  }

  //--- tracking online status of columns - note this is for columns, not nodes

  def hasOnlineColumns: Boolean = onlineColumns.nonEmpty

  def isOnlineColumn (id: String): Boolean = onlineColumns.contains(id)

  def updatedOnlineColumns (newOnlines: Iterable[String], newOfflines: Iterable[String]): Set[String] = {
    (onlineColumns ++ newOnlines) -- newOfflines
  }

  def foreachOnlineColumn (action: Column=>Unit) = {
    columnList.foreach { col=>
      if (onlineColumns.contains(col.id)) action(col)
    }
  }

  def currentColumnReachability: OnlineColumns = {
    OnlineColumns(id, currentDateTime, onlineColumns.toSeq)
  }

  //--- debugging
  def printColumnData(): Unit = {
    print("row                  |")
    columnList.foreach { col=> print(f"${PathIdentifier.name(col.id)}%15.15s |") }
    println()
    print("---------------------+") // row name
    var i = columnList.size
    while (i > 0) { print("----------------+"); i-= 1 }
    println()

    rowList.foreach { row =>
      print(f"${StringUtils.maxSuffix(row.id,20)}%-20.20s |")
      columnList.foreach { col =>
        columnDatas.get(col.id) match {
          case Some(cd) =>
            cd.get(row.id) match {
              case Some(cv) =>  print(f"${cv.valueToString}%15.15s |")
              case None => print("              - |")
            }
          case None => print("              - |")
        }
      }
      println()
    }
  }
}


/**
  * NodeInfo is how we specify Node objects
  */
object NodeInfo extends JsonConstants {
  //--- lexical constants
  val HOST = asc("host")
  val PORT = asc("port")
  val ADDR_MASK = asc("addrMask")

  // for testing purposes
  def apply (id: String): NodeInfo = {
    new NodeInfo(id, s"this is node $id", "", -1, InetAddressMask.allMatcher)
  }
}

/**
  * what we can store about a node
  * Note that host,port and addrMask are only used if we directly communicate with this node
  */
case class NodeInfo (id: String, info: String, host: String, port: Int, addrMask: InetAddressMask) extends JsonSerializable {
  import NodeInfo._

  def serializeTo (w: JsonWriter): Unit = {
    w.writeObject { _ =>
      w.writeStringMember(ID,id)
      w.writeStringMember(INFO,info)
      if (host.nonEmpty) w.writeStringMember(HOST,host)
      if (port >= 0) w.writeIntMember(PORT,port)
      if (addrMask ne InetAddressMask.allMatcher) w.writeStringMember(ADDR_MASK,addrMask.toString)
    }
  }
}

/**
  * JsonPullParser mixin to parse NodeInfo objects from:
  *   { "id":  string, "info": string, "host": string, "port": int, "ip": string }
  */
trait NodeInfoParser extends JsonPullParser {
  import NodeInfo._

  def readNodeInfo(): NodeInfo = {
    var id: String = null
    var info: String = ""
    var host: String = ""
    var port: Int = -1
    var addrMask: InetAddressMask = InetAddressMask.allMatcher

    foreachMemberInCurrentObject {
      case ID   => id = quotedValue.toString
      case INFO => info = quotedValue.toString
      case HOST => host = quotedValue.toString
      case PORT => port = unQuotedValue.toInt
      case ADDR_MASK   => addrMask = InetAddressMask(quotedValue.toString)
    }

    NodeInfo(id,info,host,port,addrMask)
  }
}



/**
  * NodeList specifies own node and optional upstream, peer and downstream nodes, all kept as NodeInfos
  */
object NodeList extends JsonConstants {
  //--- lexical constants
  val NODE_LIST = asc("nodeList")
  val SELF = asc("self")
  val UPSTREAM = asc("upstream")
  val PEER = asc("peer")
  val DOWNSTREAM = asc("downstream")

  // mostly for testing purposes
  def apply (id: String, date: DateTime,
             self: NodeInfo,
             upStream: Seq[NodeInfo] = Seq.empty,
             peers: Seq[NodeInfo] = Seq.empty,
             downStreams: Seq[NodeInfo] = Seq.empty): NodeList = {
    new NodeList( id,"this is the nodeList",date,self,
      upstreamNodes = upStream.foldLeft(SeqMap.empty[String,NodeInfo])( (acc,e)=> acc + (e.id -> e)),
      peerNodes = peers.foldLeft(SeqMap.empty[String,NodeInfo])( (acc,e)=> acc + (e.id -> e)),
      downstreamNodes = downStreams.foldLeft(SeqMap.empty[String,NodeInfo])( (acc,e)=> acc + (e.id -> e)))
  }
}

case class NodeList (
                      id: String,
                      info: String,
                      date: DateTime,
                      self: NodeInfo,
                      upstreamNodes: SeqMap[String,NodeInfo],
                      peerNodes: SeqMap[String,NodeInfo],
                      downstreamNodes: SeqMap[String,NodeInfo]
                    ) extends JsonSerializable {
import NodeList._

  def isKnownNode (nid: String): Boolean = {
    upstreamNodes.contains(nid) || peerNodes.contains(nid) || downstreamNodes.contains(nid) || (nid == id)
  }

  def downStreamIds: Seq[String] = downstreamNodes.keys.toSeq

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject { _
      .writeObject(NODE_LIST) { _
        .writeStringMember(ID, id)
        .writeStringMember(INFO, info)
        .writeDateTimeMember(DATE, date)
        .writeObject(SELF){ w=> self.serializeTo(w) }
        .writeArray(UPSTREAM) { w =>
          for (nodeInfo <- upstreamNodes.valuesIterator) {
            nodeInfo.serializeTo(w)
          }
        }
        .writeArray(PEER) { w =>
          for (nodeInfo <- peerNodes.valuesIterator) {
            nodeInfo.serializeTo(w)
          }
        }
        .writeArray(DOWNSTREAM) { w =>
          for (nodeInfo <- downstreamNodes.valuesIterator) {
            nodeInfo.serializeTo(w)
          }
        }
      }
    }
  }
}

class NodeListParser extends UTF8JsonPullParser with NodeInfoParser {
  import NodeList._

  def readNodeInfos(): SeqMap[String,NodeInfo] = {
    var map = ListMap.empty[String,NodeInfo]
    foreachElementInCurrentArray {
      val nodeInfo = readNodeInfo()
      map = map + (nodeInfo.id -> nodeInfo)
    }
    map
  }

  def readNodeList(): NodeList = {
    var id: String = null
    var info: String = ""
    var date: DateTime = DateTime.UndefinedDateTime
    var self: NodeInfo = null
    var upstream: SeqMap[String, NodeInfo] = SeqMap.empty
    var peer: SeqMap[String, NodeInfo] = SeqMap.empty
    var downstream: SeqMap[String, NodeInfo] = SeqMap.empty

    foreachMemberInCurrentObject {
      case ID => id = quotedValue.toString
      case INFO => info = quotedValue.toString
      case DATE => date = dateTimeValue
      case SELF => self = readCurrentObject( readNodeInfo() )
      case UPSTREAM => upstream = readCurrentArray( readNodeInfos() )
      case PEER => peer = readCurrentArray( readNodeInfos() )
      case DOWNSTREAM => downstream = readCurrentArray( readNodeInfos() )
    }

    if (id == null) throw exception("missing 'id' in nodeList")
    NodeList(id, info, date, self, upstream, peer, downstream)
  }

  def parse (buf: Array[Byte]): Option[NodeList] = {
    initialize(buf)
    try {
      readNextObject {
        Some( readNextObjectMember(NODE_LIST){ readNodeList() } )
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed nodeList: ${x.getMessage}")
        //x.printStackTrace()
        None
    }
  }
}


/**
  *
  */
object NodeDates extends JsonConstants {
  //--- lexical constants
  val NODE_DATES : ConstAsciiSlice = asc("nodeDates")

  val RO_COLUMNS : ConstAsciiSlice = asc("readOnlyColumns")
  val RW_COLUMNS : ConstAsciiSlice = asc("readWriteColumns")

  def apply (node: Node, externalColumnDates: Seq[ColumnDatePair], localColumnDates: Seq[(String,Seq[RowDatePair])]) = {
    new NodeDates(node.id, externalColumnDates, localColumnDates)
  }
}

/**
  * site specific snapshot of local catalog and provider data dates (last modifications)
  *
  * we distinguish between external and locally modified columns. The former we don't write on this node and hence only
  * get them through our upstream (a single update date will suffice to determine what the external needs to send to
  * update).
  * The ColumnData rows we produce ourselves could be modified concurrently between us and upstream, hence we need the
  * cell dates, i.e. check row-by-row
  *
  * this class and (our own) Node is basically the data model for node synchronization, the dynamic part of it being
  * factored out into NodeDatesResponder
  */
case class NodeDates ( nodeId: String,
                       readOnlyColumns: Seq[ColumnDatePair], // for external columns we only need the CD date
                       readWriteColumns: Seq[(String,Seq[RowDatePair])] // for locally written columns we need the CD row dates
                     ) extends JsonSerializable {
  import NodeDates._

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject {_
      .writeObject(NODE_DATES) {_
        .writeStringMember(ID, nodeId)

        // "readOnlyColumns": { "<column-id>": <date>, ... }
        .writeObject(RO_COLUMNS) { w =>
          readOnlyColumns.foreach { e =>
            w.writeDateTimeMember(e._1, e._2)
          }
        }

        // "readWriteColumns": { "<column-id>": { "<row-id>": <date>, ... }, ... }
        .writeObject(RW_COLUMNS) { w =>
          readWriteColumns.foreach { e =>
            val (colId,rowDates) = e

            w.writeObject(colId) { w=>
              rowDates.foreach { cvd =>
                w.writeDateTimeMember(cvd._1, cvd._2)
              }
            }
          }
        }
      }
    }
  }
}

/**
  * parser mixin for NodeDates
  * this is a message received through a web socket connection
  */
trait NodeDatesParser extends JsonPullParser {
  import NodeDates._

  def readROcolumns(): Seq[ColumnDatePair] = {
    val columnDates = new ArrayBuffer[ColumnDatePair]
    foreachInCurrentObject {
      columnDates += (member.toString -> unQuotedValue.toDateTime)
    }
    columnDates.toSeq
  }

  def readRWColumns(): Seq[(String,Seq[RowDatePair])] = {
    val columns = new ArrayBuffer[(String,Seq[RowDatePair])]
    foreachInCurrentObject {
      val colId = member.toString
      val rowDates = new ArrayBuffer[RowDatePair]

      foreachInCurrentObject {
        rowDates += (member.toString -> unQuotedValue.toDateTime)
      }
      columns += (colId -> rowDates.toSeq)
    }
    columns.toSeq
  }

  // the member name has already been parsed
  def parseNodeDates(): Option[NodeDates] = {
    tryParse( x=> warning(s"malformed nodeDates: ${x.getMessage}") ) {
      readNextObject {
        var nodeId: String = null
        var roColumnDates = Seq.empty[ColumnDatePair]
        var rwColumnDates = Seq.empty[(String, Seq[RowDatePair])]

        foreachMemberInCurrentObject {
          case ID => nodeId = quotedValue.toString
          case RO_COLUMNS => roColumnDates = readCurrentObject( readROcolumns() )
          case RW_COLUMNS => rwColumnDates = readCurrentObject( readRWColumns() )
        }

        if (nodeId == null) throw exception("no node id")
        NodeDates(nodeId, roColumnDates, rwColumnDates)
      }
    }
  }
}

object NodeReachabilityChange {
  def online (nodeId: String, date: DateTime) = NodeReachabilityChange( nodeId, date, true)
  def offline (nodeId: String, date: DateTime) = NodeReachabilityChange( nodeId, date, false)
}

/**
  * this is an internal message sent from NodeServerRoute or UpstreamConnectorActor, to be turned into
  * Node updates and ColumnReachabilityChange events emitted by the UpdateActor.
  *
  * Note that node reachability changes one node at a time (as opposed to column reachability).
  * We don't need Json support for this since it is only processed locally inside of a SHARE node
  */
case class NodeReachabilityChange (nodeId: String, date: DateTime, isOnline: Boolean)