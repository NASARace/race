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

import gov.nasa.race.common.UnixPath
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg tests for CellFormula, FormulaList/Parser and CellExpressionParser
  */
class FormulaListSpec extends AnyFlatSpec with RaceSpec {

  "a ColumnListParser" should "read a JSON source" in {
    val formulaSrc = FileUtils.fileContentsAsString("race-net-http-test/src/resources/sites/tabdata/data/formulaList.json").get

    println("--- parsing formulas from:")
    println(formulaSrc)

    val parser = new FormulaListParser
    parser.parse(formulaSrc.getBytes) match {
      case Some(formulaList) =>
        println("success")
      case other => fail("failed to parse formulaList.json")
    }
  }

  "a CellExpressionParser" should "compile a FormulaList agains a given Column and RowList" in {

    def getList[T] (listName: String)(f: => Option[T]): T = {
      f match {
        case Some(list) => list
        case None => fail(s"failed to parse $listName")
      }
    }

    println("--- compiling formulas")

    val columnListSrc = FileUtils.fileContentsAsString("race-net-http-test/src/resources/sites/tabdata/data/columnList.json").get
    val rowListSrc = FileUtils.fileContentsAsString("race-net-http-test/src/resources/sites/tabdata/data/rowList.json").get
    val formulaSrc = FileUtils.fileContentsAsString("race-net-http-test/src/resources/sites/tabdata/data/formulaList.json").get

    val columnList: ColumnList = getList("columnList")(new ColumnListParser().parse(columnListSrc.getBytes))
    val rowList: RowList = getList("rowList")(new RowListParser().parse(rowListSrc.getBytes))
    val formulaList: FormulaList = getList("formulaList")(new FormulaListParser().parse(formulaSrc.getBytes))
    val nodeId = UnixPath("/providers/region1/integrator")
    val funcLib = new BasicFunctionLibrary
    val selColPath = UnixPath("/providers/region1/summary")

    formulaList.compileOn(nodeId,columnList,rowList,funcLib)

    println(s"cell expressions of column '$selColPath'")
    val summaryFormulas = formulaList.getColumnFormulas(selColPath)
    summaryFormulas.foreach {e=>
      println(s" '${e._1}' : ${e._2.ce}")
    }
  }
}
