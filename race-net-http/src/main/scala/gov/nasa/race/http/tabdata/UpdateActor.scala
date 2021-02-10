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
import gov.nasa.race.common.PathIdentifier
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, PublishingRaceActor, RaceContext, RaceException, SubscribingRaceActor}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, Success, ifSome}

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.existentials

/**
  * the actor that initializes and updates the local Node data
  */
class UpdateActor (val config: Config) extends SubscribingRaceActor with PublishingRaceActor
                                                              with ContinuousTimeRaceActor with PeriodicRaceActor {

  val nodeId: String = config.getString("node-id")
  val upstreamId: Option[String] = config.getOptionalString("upstream-id")

  var node = initializeNode // node objects are immutable

  val funcLib: CellFunctionLibrary = getConfigurableOrElse("functions")(new CellFunctionLibrary)

  val cvFormulaList: Option[CellValueFormulaList] = readCellValueFormulaList(node,funcLib)
  val constraintFormulaList: Option[ConstraintFormulaList] = readConstraintFormulaList(node,funcLib)

  //--- to accumulate constraint changes due to value or time triggered data changes
  val newViolations = ArrayBuffer.empty[ConstraintFormula]
  val newResolved = ArrayBuffer.empty[ConstraintFormula]

  val ctx: BasicEvalContext = new BasicEvalContext(node.id, node.rowList, DateTime.UndefinedDateTime, node.columnDatas) // this is a mutable object

  initColumnData()  // this computes missing cells

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
      ifSome(cvFormulaList) { fl=>
        if (fl.hasAnyTimeTrigger) {
          fl.armTriggeredAt(currentSimTime)
          startScheduler
        }
      }
      true
    } else false
  }

  override def onRaceTick(): Unit = {
    processTimeTriggeredFormulas()
  }

  override def handleMessage: Receive = {
    case BusEvent(_, cdc:ColumnDataChange, _) => processColumnDataChange(cdc)
    case BusEvent(_, nrc: NodeReachabilityChange, _) => processNodeReachabilityChange(nrc)
  }

  //--- initialization

  def initializeNode: Node = {
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
      val parser = new ColumnListParser(nodeId)
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
      val colName = PathIdentifier.name(col.id)
      val f = new File(dataDir, s"$colName.json")
      info(s"reading column data for '$colName' from $f")
      if (f.isFile) {
        val parser = new ColumnDataParser(rowList)
        parser.setLogging(info,warning,error)
        parser.parse(FileUtils.fileContentsAsBytes(f).get) match {
          case Some(cd) => map += col.id -> cd
          case None => throw new RaceException(f"error parsing column data in $f")
        }
      } else {
        warning(s"no column data for '$colName'")
        // init with Date0 so that we always try to update from remote
        map += col.id -> ColumnData(col.id,DateTime.Date0,Map.empty[String,CellValue[_]])
      }
    }

    map.toMap
  }

  def parseFormulas[T <: FormulaList](node: Node, key: String, parser: FormulaListParser[T]): Option[T] = {
    readList[T](key) { input =>
      parser.setLogging(info, warning, error)
      parser.parse(input).map { fList =>
        fList.compileWith(node, funcLib) match {
          case Failure(err) => throw new RuntimeException(s"error compiling $key: $err")
          case Success => fList
        }
      }
    }
  }

  def readCellValueFormulaList (node: Node, funcLib: CellFunctionLibrary): Option[CellValueFormulaList] = {
    parseFormulas( node, "value-formulas", new CellValueFormulaListParser)
  }

  def readConstraintFormulaList (node: Node, funcLib: CellFunctionLibrary): Option[ConstraintFormulaList] = {
    parseFormulas( node, "constraint-formulas", new ConstraintFormulaListParser)
  }

  //--- data modification

  def updateNode (cd: ColumnData): Unit = {
    val colId = cd.id
    if (node.columnDatas(colId) ne cd) {
      node = node.copy(columnDatas = node.columnDatas + (colId -> cd))
      publish(node)
    }
  }

  // ctx is already changed
  def updateAndPublish (colId: String, changeNodeId: String, cvs: Seq[CellPair]): Unit = {
    if (cvs.nonEmpty) {
      val cd = ctx.cellValues(colId)
      updateNode(cd)

      val cdc = ColumnDataChange(colId, changeNodeId, ctx.evalDate, cvs)
      publish(cdc)
    }
  }

  // ctx has not been changed yet
  def setAndPublishOwnChange(colId: String, cvs: Seq[CellPair]): Unit = {
    ctx.setCellValues(colId, cvs).ifSuccess {
      val cd = ctx.cellValues(colId)
      updateNode(cd)

      val cdc = ColumnDataChange(colId, node.id, ctx.evalDate, cvs)
      publish(cdc)
    }
  }

  def publishConstraintChange(): Unit = {
    if (newViolations.nonEmpty || newResolved.nonEmpty) {
      publish(node) // current violations are stored in the node, publish this first to ensure it is always consistent with change messages
      publish( ConstraintChange( currentSimTime, newViolations.toSeq, newResolved.toSeq))
    }
  }

  //--- mutation

  /**
    * init-time mutation (we are not connected yet)
    * eval undefined cells for which we have formulas that have all their dependencies satisfied (which might be
    * recursive, so execute in order of formulaList definition).
    */
  def initColumnData(): Unit = {
    ctx.evalDate = DateTime.now
    var newCds = node.columnDatas

    ifSome(cvFormulaList) { fl=>
      fl.foreachValueTriggeredColumn { (colId,formulas) =>
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

    newViolations.clear()
    newResolved.clear()

    if (setChangedColumnData(cdc)) {
      evaluateValueTriggeredFormulas(cdc)
      publishConstraintChange()
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
          (row.id, cv) +: list
        }
        updateAndPublish(colId,cdc.changeNodeId,cvs)
        true

      case Failure(reason) =>
        warning(s"columnData change rejected: $reason")
        false
    }
  }

  def processConstraintEval(cf: ConstraintFormula, date: DateTime, isSatisfied: Boolean): Unit = {
    val wasViolated = node.hasConstraintViolation(cf)

    if (isSatisfied == wasViolated) { // means we have a change
      if (!isSatisfied) {
        info(s"new constraint violation: ${cf.id}: ${cf.info}")
        newViolations += cf
        node = node.setConstraintViolation(cf)
      } else {
        info(s"resolved constraint violation: ${cf.id}: ${cf.info}")
        newResolved += cf
        node = node.resetConstraintViolation(cf)
      }
    }
  }

  def evaluateValueTriggeredFormulas (cdc: ColumnDataChange): Unit = {
    ifSome(cvFormulaList) { vfl=>
      ctx.evalDate = updatedSimTime
      vfl.evaluateValueTriggeredFormulas(ctx)(setAndPublishOwnChange)

      if (ctx.hasChanges) {
        ifSome(constraintFormulaList) { cfl=>
          cfl.evaluateValueTriggeredFormulas(ctx)(processConstraintEval)
        }
      }
    }
  }

  def processTimeTriggeredFormulas(): Unit = {
    // TODO - make sure we don't evaluate until we are synced with (optional) upstream

    ctx.columnDatas = node.columnDatas
    ctx.evalDate = updatedSimTime
    ctx.clearChanges()

    newViolations.clear()
    newResolved.clear()

    ifSome(cvFormulaList) { fl=>
      fl.evaluateTimeTriggeredFormulas(ctx)(setAndPublishOwnChange)

      if (ctx.hasChanges) {
        fl.evaluateValueTriggeredFormulas(ctx)(setAndPublishOwnChange)
        ifSome(constraintFormulaList) { _.evaluateValueTriggeredFormulas(ctx)(processConstraintEval) }
      }
    }

    ifSome(constraintFormulaList) { _.evaluateTimeTriggeredFormulas(ctx)(processConstraintEval) }
    publishConstraintChange()
  }

  def processNodeReachabilityChange (nrc: NodeReachabilityChange): Unit = {
    info(s"got $nrc")
    // TODO
  }
}
