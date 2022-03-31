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
import gov.nasa.race.common.{Clock, ConstAsciiSlice, InetAddressMatcher, JsonMessageObject, JsonParseException, JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.StringUtils

import scala.collection.immutable.{ListMap, SeqMap}
import scala.collection.mutable
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
  def isUpstreamId(nid: CharSequence): Boolean = upstreamId.isDefined && upstreamId.get == nid

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

  def isOwnColumn(colId: String): Boolean = {
    columnList.columns.get(colId) match {
      case Some(col) => isColumnOwner(col,id)
      case None => false
    }
  }

  def isColumnOwner(col: Column, nodeId: String): Boolean = {
    col.owner match {
      case "<self>" | "." => nodeId == id
      case "<up>" => upstreamId.isDefined && nodeId == upstreamId.get
      case owner => nodeId == owner
    }
  }

  def columnOwner (colId: String): Option[String] = {
    columnList.get(colId).map(columnOwner)
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

  //--- send/receive filters

  def foreachColumnReceivedFrom (nodeId: String)(f: Column=>Unit): Unit = {
    columnList.columns.foreach { e=>
      val col = e._2
      if (col.isReceivedFrom(nodeId)(this)) f(col)
    }
  }

  def columnsReceivedFrom (nodeId: String): Seq[Column] = {
    columnList.columns.foldRight(Seq.empty[Column]) { (e,acc)=>
      val col = e._2
      if (col.isReceivedFrom(nodeId)(this)) col +: acc else acc
    }
  }

  def columnsSentTo (nodeId: String): Seq[Column] = {
    columnList.columns.foldRight(Seq.empty[Column]) { (e,acc)=>
      val col = e._2
      if (col.isSentTo(nodeId)(this)) col +: acc else acc
    }
  }

  def foreachRowSentTo (nodeId: String, col: Column)(f: Row[_]=>Unit): Unit = {
    rowList.rows.foreach { e=>
      val row = e._2
      if (row.isSentTo(nodeId)(this,col)) f(row)
    }
  }

  def columnRowsSentTo (nodeId: String, col: Column): Seq[Row[_]] = {
    if (col.isSentTo(nodeId)(this)) {
      rowList.rows.foldRight( Seq.empty[Row[_]]) { (e,acc)=>
        val row = e._2
        if (row.isSentTo(nodeId)(this,col)) row +: acc else acc
      }
    } else Seq.empty[Row[_]]
  }

  //--- node date creation

  def nodeDatesFor (nodeId: String, date: DateTime = currentDateTime): NodeDates = {
    val columnDataDates = mutable.Buffer.empty[ColumnDatePair]
    val columnDataRowDates = mutable.Buffer.empty[(String,Seq[RowDatePair])]

    foreachColumnReceivedFrom(nodeId) { col=>
      if (col.isSentTo(nodeId)(this)) { // both id and we write this data, we need CD row dates
        columnDatas.get(col.id) match {
          case Some(cd) =>
            val rdps = mutable.Buffer.empty[RowDatePair]
            foreachRowSentTo(nodeId, col) { row=>
              cd.get(row.id) match {
                case Some(cv) => rdps += (row.id -> cv.date)
                case None => rdps += (row.id -> DateTime.Date0)
              }
            }
            columnDataRowDates += (col.id -> rdps.toSeq)
          case None =>
            columnDataRowDates += (col.id -> columnRowsSentTo(nodeId,col).map( row=> (row.id, DateTime.Date0)))
        }
      } else { // we only receive this column, CD date will suffice
        columnDatas.get(col.id) match {
          case Some(cd) => columnDataDates += (col.id -> cd.date)
          case None => columnDataDates += (col.id -> DateTime.Date0)
        }
      }
    }

    NodeDates( id, date, columnDataDates.toSeq, columnDataRowDates.toSeq)
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

  def onlineColumnIdsFor (nodeId: String): Seq[String] = {
    columnList.columns.foldRight(Seq.empty[String]){ (e,acc) =>
      val col = e._2
      if (onlineNodes.contains(resolveOwner(col.owner)) && col.isSentTo(nodeId)(this)) col.id +: acc else acc
    }
  }

  def onlineDownstreamIds: Seq[String] = nodeList.downstreamIds.filter( onlineNodes.contains)

  /**
    * filter reachability changes we receive from upstream to our defined peer nodes
    * this happens in the UC
    */
  def getPeerReachabilityChange (nrc: NodeReachabilityChange): Option[NodeReachabilityChange] = nrc.filter(nodeList.peerNodes.contains)


  /**
    * filter reachabilityChanges to downstream nodes
    * this happens in the NSR
    */
  def getDownstreamReachabilityChange (nrc: NodeReachabilityChange): Option[NodeReachabilityChange] = nrc.filter(nodeList.downstreamNodes.contains)

  def onlinePeerIds: Seq[String] = nodeList.peerIds.filter( onlineNodes.contains)

  //--- misc

  def resolveOwner (owner: String): String = {
    if (owner == "<self>" || owner == ".") id
    else if (owner == "<up>") upstreamId.getOrElse("")
    else owner
  }

  //--- debugging

  def printColumnData(): Unit = {
    print("row                  │")
    columnList.foreach { col=> print(f"${PathIdentifier.name(col.id)}%15.15s │") }
    println()
    print("━━━━━━━━━━━━━━━━━━━━━┿")
    var i = columnList.size-1
    while (i > 0) { print("━━━━━━━━━━━━━━━━┿"); i-= 1 }
    print("━━━━━━━━━━━━━━━━┥")
    println()

    rowList.foreach { row =>
      print(f"${StringUtils.maxSuffix(row.id,20)}%-20.20s │")

      columnList.foreach { col =>
        columnDatas.get(col.id) match {
          case Some(cd) =>
            cd.get(row.id) match {
              case Some(cv) =>  print(f"${cv.valueToString}%15.15s │")
              case None => print("              - │")
            }
          case None => print("              - │")
        }
      }
      println()
    }
  }

  def printOnlineNodes(): Unit = {
    println("online nodes")
    onlineNodes.foreach(n=> println(s"  $n"))
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

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeStringMember(ID,id)
    w.writeStringMember(INFO,info)
    if (host.nonEmpty) w.writeStringMember(HOST,host)
    if (port >= 0) w.writeIntMember(PORT,port)
    if (protocol.nonEmpty) w.writeStringMember(PROTOCOL,protocol)
    if (addrMask ne InetAddressMatcher.allMatcher) w.writeStringMember(ADDR_MASK,addrMask.toString)
  }

  /**
    * serialize without connectivity data, only id and info
    */
  def shortSerializeTo (w: JsonWriter): Unit = {
    w.writeObject { _ =>
      w.writeStringMember(ID,id)
      w.writeStringMember(INFO,info)
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
                    ) extends JsonMessageObject {
  import NodeList._

  def isKnownNode (nid: String): Boolean = {
    upstreamNodes.contains(nid) || peerNodes.contains(nid) || downstreamNodes.contains(nid) || (nid == id)
  }

  def downstreamIds: Seq[String] = downstreamNodes.keys.toSeq

  def peerIds: Seq[String] = peerNodes.keys.toSeq

  def _serializeMembersTo (w: JsonWriter)(serializeNodeInfo: (NodeInfo,JsonWriter)=>Unit): Unit = {
    w.writeObjectMember(NODE_LIST) { _
      .writeStringMember(ID, id)
      .writeStringMember(INFO, info)
      .writeDateTimeMember(DATE, date)

      .writeMember(SELF){ w=>
        serializeNodeInfo(self,w)
      }
      .writeArrayMember(UPSTREAM) { w =>
        for (nodeInfo <- upstreamNodes.valuesIterator) {
          serializeNodeInfo(nodeInfo,w)
        }
      }
      .writeArrayMember(PEER) { w =>
        for (nodeInfo <- peerNodes.valuesIterator) {
          serializeNodeInfo(nodeInfo,w)
        }
      }
      .writeArrayMember(DOWNSTREAM) { w =>
        for (nodeInfo <- downstreamNodes.valuesIterator) {
          serializeNodeInfo(nodeInfo,w)
        }
      }
    }
  }

  def serializeMembersTo (w: JsonWriter): Unit = _serializeMembersTo(w)( (ni,w)=> ni.serializeTo(w))
  def shortSerializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject{ w=>
      _serializeMembersTo(w)( (ni,w)=> ni.shortSerializeTo(w))
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

  val RO_COLUMNS : ConstAsciiSlice = asc("columnDataDates")
  val RW_COLUMNS : ConstAsciiSlice = asc("columnDataRowDates")

  def apply (node: Node, externalColumnDates: Seq[ColumnDatePair], localColumnDates: Seq[(String,Seq[RowDatePair])]) = {
    new NodeDates(node.id, node.currentDateTime, externalColumnDates, localColumnDates)
  }
}

/**
  * receiver specific snapshot of ColumnData and ColumnData row dates
  *
  * for Columns we only send to the external node we just need the (high watermark) ColumnData date
  *
  * for Columns we both send to and receive from the external node we need the row dates of the ColumnData
  */
case class NodeDates (nodeId: String,
                      date: DateTime,
                      columnDataDates: Seq[ColumnDatePair],
                      columnDataRowDates: Seq[(String,Seq[RowDatePair])]
                     ) extends JsonMessageObject {
  import NodeDates._

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeObjectMember(NODE_DATES) {_
      .writeStringMember(ID, nodeId)
      .writeDateTimeMember(DATE, date)

      // "columnDataDates": { "<column-id>": <date>, ... }
      .writeObjectMember(RO_COLUMNS) { w =>
        columnDataDates.foreach { e =>
          w.writeDateTimeMember(e._1, e._2)
        }
      }

      // "columnDataRowDates": { "<column-id>": { "<row-id>": <date>, ... }, ... }
      .writeObjectMember(RW_COLUMNS) { w =>
        columnDataRowDates.foreach { e =>
          val (colId,rowDates) = e

          w.writeObjectMember(colId) { w=>
            rowDates.foreach { cvd =>
              w.writeDateTimeMember(cvd._1, cvd._2)
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
        var date = DateTime.UndefinedDateTime
        var roColumnDates = Seq.empty[ColumnDatePair]
        var rwColumnDates = Seq.empty[(String, Seq[RowDatePair])]

        foreachMemberInCurrentObject {
          case ID => nodeId = quotedValue.toString
          case DATE => date = dateTimeValue
          case RO_COLUMNS => roColumnDates = readCurrentObject( readROcolumns() )
          case RW_COLUMNS => rwColumnDates = readCurrentObject( readRWColumns() )
        }

        if (nodeId == null) throw exception("no node id")
        NodeDates(nodeId, date, roColumnDates, rwColumnDates)
      }
    }
  }
}

object NodeReachabilityChange extends JsonConstants {
  def online (date: DateTime, nodeId: String) = NodeReachabilityChange( date, Seq(nodeId), Seq.empty[String])
  def online (date: DateTime, nodeIds: Seq[String]) = NodeReachabilityChange( date, nodeIds, Seq.empty[String])

  def offline (date: DateTime, nodeId: String) = NodeReachabilityChange( date, Seq.empty[String], Seq(nodeId))
  def offline (date: DateTime, nodeIds: Seq[String]) = NodeReachabilityChange( date, Seq.empty[String], nodeIds)

  val NODE_REACHABILITY_CHANGE = asc("nodeReachabilityChange")
  val ONLINE = asc("online")
  val OFFLINE = asc("offline")
}

/**
  * a connectivity change detected by NodeServerRoute or UpstreamConnectorActor
  *
  * the NSR sends its online downstreams to the UC as part of the sync protocol.
  * the USR sends it to devices in case we establish/loose upstream connection
  */
case class NodeReachabilityChange (date: DateTime, online: Seq[String], offline: Seq[String]) extends JsonMessageObject {
  import NodeReachabilityChange._

  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeObjectMember(NODE_REACHABILITY_CHANGE) { w=>
      w.writeDateTimeMember(DATE, date)
      if (online.nonEmpty) w.writeStringArrayMember(ONLINE, online)
      if (offline.nonEmpty) w.writeStringArrayMember(OFFLINE, offline)
    }
  }

  def filter (p: String=>Boolean): Option[NodeReachabilityChange] = {
    val filteredOnline = online.filter(p)
    val filteredOffline = offline.filter(p)

    if (filteredOnline.isEmpty && filteredOffline.isEmpty) None  // nothing left after filter
    else if ((filteredOnline eq online) && (filteredOffline eq offline)) Some(this) // no changes
    else  Some(NodeReachabilityChange(date,filteredOnline,filteredOffline))
  }

  def isEmpty: Boolean = online.isEmpty && offline.isEmpty

  def isSingleOnlineChange (nodeId: String): Boolean = offline.isEmpty && ((online.size == 1) && online.head == nodeId)
}

trait NodeReachabilityChangeParser extends JsonPullParser {
  import NodeReachabilityChange._

  def parseNodeReachabilityChange(): Option[NodeReachabilityChange] = {
    tryParse( x=> warning(s"malformed nodeReachabilityChange: ${x.getMessage}")) {
      readCurrentObject {
        var date = DateTime.UndefinedDateTime
        var online = Seq.empty[String]
        var offline = Seq.empty[String]

        foreachMemberInCurrentObject {
          case DATE => date = dateTimeValue
          case ONLINE => online = readCurrentStringArray()
          case OFFLINE => offline = readCurrentStringArray()
        }

        NodeReachabilityChange(date,online,offline)
      }
    }
  }
}