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
import gov.nasa.race.common.{Clock, ConstAsciiSlice, InetAddressMatcher, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.StringUtils

import scala.collection.immutable.{ListMap, SeqMap}
import scala.collection.mutable.ArrayBuffer

object Node {
  def apply (nl: NodeList, cl: ColumnList, rl: RowList, cds: Map[String,ColumnData], up: Option[String] = None, clk: Clock = Clock.wallClock): Node  = {
    new Node( nl, cl, rl, cds, up, clk, Set.empty[ConstraintFormula], Set(nl.self.id))
  }
}

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
case class Node (
                  //--- semi-static config lists
                  nodeList: NodeList,
                  columnList: ColumnList,
                  rowList: RowList,

                  //--- variable data
                  columnDatas: Map[String,ColumnData],

                  //--- variable state
                  upstreamId: Option[String],  // the currently selected upstream (there can only be one)
                  clock: Clock,
                  violatedConstraints: Set[ConstraintFormula],
                  onlineNodes: Set[String]
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

  def getUpstreamNodeInfo: Option[NodeInfo] =  upstreamId.flatMap(nodeList.upstreamNodes.get)


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

  def foreachOrderedColumnData (f: ColumnData=> Unit): Unit = {
    columnList.columns.foreach { e=>
      columnDatas.get(e._1) match {
        case Some(cd) => f(cd)
        case None =>
      }
    }
  }

  def foreachOrderedRow (cd: ColumnData)(f: (Row[_],CellValue[_])=>Unit): Unit = {
    rowList.rows.foreach { e=>
      cd.get(e._1) match {
        case Some(cv) => f(e._2,cv)
        case None =>
      }
    }
  }

  //--- column ownership (this needs to be here to support abstract owners)

  def isColumnOwner(col: Column, nodeId: String): Boolean = {
    col.owner match {
      case "<self>" | "." => nodeId == id
      case "<up>" => upstreamId.isDefined && nodeId == upstreamId.get
      case owner => nodeId == owner
    }
  }

  def columnOwner (col: Column): String = {
    col.owner match {
      case "<self>"  | "." => id
      case "<up>" => if (upstreamId.isDefined) upstreamId.get else "<up>"
      case owner => owner
    }
  }

  def columnsOwnedBy (nodeId: String): Seq[Column] = {
    columnList.columns.foldRight(Seq.empty[Column]){ (e,acc) =>
      val col = e._2
      if (isColumnOwner(col, nodeId)) col +: acc else acc
    }
  }

  def columnIdsOwnedBy (nodeId: String): Seq[String] = {
    columnList.columns.foldRight(Seq.empty[String]){ (e,acc) =>
      val col = e._2
      if (isColumnOwner(col, nodeId)) col.id +: acc else acc
    }
  }

  //--- reachability

  /**
    * the inverse of our onlineNodes member
    */
  def offlineNodes: Seq[String] = {
    val offlinePeers = nodeList.peerNodes.keys.filterNot( onlineNodes.contains )
    val offlineDownstreams = nodeList.downstreamNodes.keys.filterNot( onlineNodes.contains )
    val offlineUpstream = if (upstreamId.isDefined && !onlineNodes.contains(upstreamId.get)) Seq(upstreamId.get) else Seq.empty
    offlineUpstream ++ offlineDownstreams ++ offlinePeers
  }

  def onlineColumns : Seq[Column] = {
    columnList.columns.foldRight(Seq.empty[Column]){ (e,acc) =>
      val col = e._2
      if (onlineNodes.contains(columnOwner(col))) col +: acc else acc
    }
  }

  def onlineColumnIds : Seq[String] = {
    columnList.columns.foldRight(Seq.empty[String]){ (e,acc) =>
      val ownerId = columnOwner(e._2)
      if (onlineNodes.contains(ownerId)) e._1 +: acc else acc
    }
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
  val PROTOCOL = asc("protocol")
  val HOST = asc("host")
  val PORT = asc("port")
  val ADDR_MASK = asc("addrMask")

  // for testing purposes
  def apply (id: String): NodeInfo = {
    new NodeInfo(id, s"this is node $id", "", -1, "http", InetAddressMatcher.allMatcher)
  }
}

/**
  * what we can store about a node
  * Note that host,port and addrMask are only used if we directly communicate with this node
  */
case class NodeInfo (id: String, info: String, host: String, port: Int, protocol: String, addrMask: InetAddressMatcher) extends JsonSerializable {
  import NodeInfo._

  def serializeTo (w: JsonWriter): Unit = {
    w.writeObject { _ =>
      w.writeStringMember(ID,id)
      w.writeStringMember(INFO,info)
      if (host.nonEmpty) w.writeStringMember(HOST,host)
      if (port >= 0) w.writeIntMember(PORT,port)
      if (protocol.nonEmpty) w.writeStringMember(PROTOCOL,protocol)
      if (addrMask ne InetAddressMatcher.allMatcher) w.writeStringMember(ADDR_MASK,addrMask.toString)
    }
  }

  def getUri: Option[String] = {
    if (host.nonEmpty) {
      val sb = new StringBuilder

      if (protocol.nonEmpty) {
        sb.append(protocol)
        if (!protocol.endsWith("://")) sb.append("://")
      }

      sb.append(host)

      if (port >= 0) {
        sb.append(':')
        sb.append(port)
      }

      Some(sb.toString())

    }  else None // no URI without at least a host spec
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
    var protocol: String = ""
    var addrMask: InetAddressMatcher = InetAddressMatcher.allMatcher

    foreachMemberInCurrentObject {
      case ID   => id = quotedValue.toString  // has to be explicit since we can't resolve without assuming member order
      case INFO => info = quotedValue.toString
      case HOST => host = quotedValue.toString
      case PORT => port = unQuotedValue.toInt
      case PROTOCOL => protocol = quotedValue.intern
      case ADDR_MASK   => addrMask = InetAddressMatcher(quotedValue.toString)
    }

    NodeInfo(id,info,host,port,protocol,addrMask)
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
      readCurrentObject {
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
  * a connectivity change detected by NodeServerRoute or UpstreamConnectorActor
  *
  * note this changes one node at a time
  */
case class NodeReachabilityChange (nodeId: String, date: DateTime, isOnline: Boolean) extends JsonSerializable {

  override def serializeTo(w: JsonWriter): Unit = {
    w.clear().writeObject { _
      .writeObject("nodeReachabilityChange") {_
        .writeStringMember("nodeId", nodeId)
        .writeDateTimeMember("date", date)
        .writeBooleanMember("isOnline", isOnline)
      }
    }
  }
}