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

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, RaceContext, RaceException, SubscribingRaceActor}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, Success}

import java.io.File
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * the actor that initializes and updates the local Node data
  */
class UpdateActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor
                                                              with ContinuousTimeRaceActor with PeriodicRaceActor {

  var node = initializeNode // node objects are immutable

  val funcLib: CellFunctionLibrary = getConfigurableOrElse("functions")(new CellFunctionLibrary)

  val cvFormulaList: CellValueFormulaList = readCellValueFormulaList(node,funcLib)
  val constraintFormulaList: ConstraintFormulaList = readConstraintFormulaList(node,funcLib)

  val ctx: BasicEvalContext = new BasicEvalContext(node.id, node.rowList, DateTime.UndefinedDateTime, node.columnDatas) // this is a mutable object


  override def defaultTickInterval: FiniteDuration = 30.seconds

  def getNode: Node = node  // for debugging purposes

  //--- RACE callbacks

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config): Boolean = {
    if (super.onInitializeRaceActor(raceContext, actorConf)) { // super has to init first so that we are properly connected
      publish(node)
      true
    } else false
  }

  override def onStartRaceActor (originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)) {
      if (cvFormulaList.hasAnyTimeTrigger) {
        cvFormulaList.armTriggeredAt(currentSimTime)
        startScheduler
      }
      true
    } else false
  }

  override def onRaceTick(): Unit = {
    processTimeTriggeredFormulas()
  }

  override def handleMessage: Receive = {
    case BusEvent(_, cdc:ColumnDataChange, _) => processColumnDataChange(cdc)
    //case BusEvent(_, nrc: NodeReachabilityChange, _) => processNodeReachabilityChange(nrc)
  }

  //--- initialization

  def initializeNode: Node = {
    val nodeId: String = config.getString("node-id")
    val upstreamId: Option[String] = config.getOptionalString("upstream-id")

    val columnList: ColumnList = readColumnList
    val rowList: RowList = readRowList(columnList)
    val colDatas: Map[String,ColumnData] = readColumnData(columnList,rowList)

    Node(nodeId,upstreamId,columnList,rowList,colDatas)
  }

  protected def readList[T](key: String)(f: Array[Byte]=>Option[T]): Option[T] = {
    val pn = config.getOptionalString(key)
    if (pn.isDefined) {
      info(s"translating file ${pn.get}")
      config.translateFile(key)(f)
    } else None
  }

  def readColumnList: ColumnList = {
    readList[ColumnList]("column-list"){ input=>
      val parser = new ColumnListParser
      parser.setLogging(info,warning,error)
      parser.parse(input)
    }.getOrElse( throw new RuntimeException("missing or invalid column-list"))
  }

  def readRowList (columnList: ColumnList): RowList = {
    readList[RowList]("row-list") { input =>
      val parser = new RowListParser(columnList.id)
      parser.setLogging(info, warning, error)
      parser.parse(input)
    }.getOrElse( throw new RuntimeException("missing or invalid row-list"))
  }

  def readColumnData (columnList: ColumnList, rowList: RowList): Map[String,ColumnData] = {
    val map = mutable.Map.empty[String,ColumnData]

    val dataDir = config.getExistingDir("data-dir")
    for (p <- columnList.columns) {
      val col = p._2
      val colId = col.id
      val f = new File(dataDir, s"$colId.json")
      info(s"reading column data for '$colId' from $f")
      if (f.isFile) {
        val parser = new ColumnDataParser(rowList)
        parser.setLogging(info,warning,error)
        parser.parse(FileUtils.fileContentsAsBytes(f).get) match {
          case Some(columnData) => map += colId -> columnData
          case None => throw new RaceException(f"error parsing column data in $f")
        }
      } else {
        warning(s"no column data for '$colId'")
        // init with Date0 so that we always try to update from remote
        map += colId -> ColumnData(col.id,DateTime.Date0,Map.empty[String,CellValue[_]])
      }
    }

    map.toMap
  }

  def parseFormulas[T <: FormulaList](key: String, parser: FormulaListParser[T]): T = {
    readList[T](key){ input=>
      parser.setLogging(info,warning,error)
      parser.parse(input).map { fList=>
        fList.compileWith(node,funcLib) match {
          case Failure(err) => throw new RuntimeException(s"error compiling $key: $err")
          case Success => fList
        }
      }
    }.getOrElse( throw new RuntimeException("invalid $key"))
  }

  def readCellValueFormulaList (node: Node, funcLib: CellFunctionLibrary): CellValueFormulaList = {
    parseFormulas("value-formulas", new CellValueFormulaListParser)
  }

  def readConstraintFormulaList (node: Node, funcLib: CellFunctionLibrary): ConstraintFormulaList = {
    parseFormulas("constraint-formulas", new ConstraintFormulaListParser)
  }

  //--- data modification

  def updateNode (cd: ColumnData): Unit = {
    val colId = cd.id
    if (node.columnDatas(colId) ne cd) {
      node = node.copy(columnDatas = node.columnDatas + (colId -> cd))
      publish(node)
    }
  }

  def updateAndPublish (colId: String, changeNodeId: String, cvs: Seq[CellPair]): Unit = {
    val cd = ctx.cellValues(colId)
    updateNode(cd)

    if (cvs.nonEmpty) {
      val cdc = ColumnDataChange(colId, changeNodeId, ctx.evalDate, cvs)
      publish(cdc)
    }
  }

  def updateAndPublishOwnChange (colId: String, cvs: Seq[CellPair]): Unit = updateAndPublish(colId,node.id,cvs)

  //--- mutation

  /**
    * init-time mutation (we are not connected yet)
    * eval undefined cells for which we have formulas that have all their dependencies satisfied (which might be
    * recursive, so execute in order of formulaList definition).
    */
  def initColumnData: Unit = {
    ctx.evalDate = DateTime.now
    var newCds = node.columnDatas

    cvFormulaList.foreachValueTriggeredColumn { (colId,formulas) =>
      ctx.clearChanges()

      formulas.foreach { e=>
        val rowId = e._1
        val formula: CellFormula[_] = e._2

        if (!ctx.hasCellValue(colId,rowId)) { // we don't have a value for this row yet..
          if (formula.canEvaluateIn(ctx)) {   //   ..but we have values for all its dependencies
            val cv = formula.computeCellValue(ctx)
            ctx.setCellValue(colId,rowId,cv)
          }
        }
      }

      if (ctx.hasChanges) {
        newCds = newCds + (colId -> ctx.cellValues(colId))
      }
    }

    if (newCds ne node.columnDatas) {
      node = node.copy( columnDatas = newCds)
      // no need to publish anything yet
    }
  }

  /**
    * main entry point for both local and remote data changes
    * 〖 ensures the following guarantees:
    *    - changes happen one column at a time (CDC is single column only)
    *    - explicit (CDC) and automated (formula) changes are published as separate events
    *    - order of change is (1) CDC -> (2) formulas in order of definition
    *  〗
    */
  def processColumnDataChange(cdc: ColumnDataChange): Unit = {
    ctx.columnDatas = node.columnDatas
    ctx.clearChanges()

    if (setChangedColumnData(cdc)) {
      evaluateValueTriggeredFormulas(cdc)
      //checkCellValueConstraints
    }
  }

  /**
    * only set changed values that are outdated, return true if we have any changed cells
    */
  def setChangedColumnData (cdc: ColumnDataChange): Boolean = {
    ctx.evalDate = cdc.date
    val colId = cdc.columnId

    ctx.setCellValues(colId, cdc.changedValues) match {
      case Success =>
        val cvs = ctx.foldRightChanges(Seq.empty[(String,CellValue[_])]){ (list,_,row,cv)=>
          val e = new Tuple2[String,CellValue[_]](row.id, cv)
          e +: list
        }
        updateAndPublish(colId,cdc.changeNodeId,cvs)
        true

      case Failure(reason) =>
        warning(s"columnData change rejected: $reason")
        false
    }
  }

  def reportConstraintViolation (cf: ConstraintFormula): Unit = {
    println(s"violated constraint formula: $cf")
  }

  def evaluateValueTriggeredFormulas (cdc: ColumnDataChange): Unit = {
    ctx.evalDate = updatedSimTime
    cvFormulaList.evaluateValueTriggeredFormulas(ctx)(updateAndPublishOwnChange)
    if (ctx.hasChanges) constraintFormulaList.evaluateValueTriggeredFormulas(ctx)(reportConstraintViolation)
  }

  def processTimeTriggeredFormulas(): Unit = {

    // TODO - make sure we don't evaluate until we are synced with (optional) upstream

    ctx.columnDatas = node.columnDatas
    ctx.evalDate = updatedSimTime
    ctx.clearChanges()

    cvFormulaList.evaluateTimeTriggeredFormulas(ctx)(updateAndPublishOwnChange)
    if (ctx.hasChanges) {
      cvFormulaList.evaluateValueTriggeredFormulas(ctx)(updateAndPublishOwnChange)
      constraintFormulaList.evaluateValueTriggeredFormulas(ctx)(reportConstraintViolation)
    }

    constraintFormulaList.evaluateTimeTriggeredFormulas(ctx)(reportConstraintViolation)
  }
}
