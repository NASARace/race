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

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg tests for CellFormula, FormulaList/Parser and CellExpressionParser
  */
class CellValueFormulaListSpec extends AnyFlatSpec with RaceSpec with NodeDependentTest {


  "a ColumnListParser" should "read a JSON source" in {
    val nodeId = "/nodes/node_2"
    val dataDir = "src/resources/data/node_2"

    val node = getNode(nodeId, dataDir)
    val formulaSrc = FileUtils.fileContentsAsString(dataDir + "/formulaList.json").get

    println("\n--- parsing cell value formulas from:")
    println(formulaSrc)

    val parser = new CellValueFormulaListParser
    parser.parse(formulaSrc.getBytes) match {
      case Some(formulaList) =>
        println("success:")
        formulaList.printFormulaSpecs()
      case other => fail("failed to parse formulaList.json")
    }
  }

  "a CellExpressionParser" should "compile a FormulaList against a given Column and RowList" in {

    val nodeId = "/nodes/coordinator"
    val dataDir = "src/resources/data/coordinator"
    val node = getNode(nodeId, dataDir)
    val funcLib = new CellFunctionLibrary

    println("\n--- compiling cell value formulas")

    val formulaSrc = FileUtils.fileContentsAsString(dataDir + "/formulaList.json").get
    val parser = new CellValueFormulaListParser
    parser.parse(formulaSrc.getBytes) match {
      case Some(formulaList) =>
        println("formula sources:")
        formulaList.printFormulaSpecs()

        val selColPath = "/columns/summary"
        println(s"compiling formula expressions for column: $selColPath")
        formulaList.compileWith(node,funcLib)

        println(s"cell expressions of column '$selColPath'")
        val summaryFormulas = formulaList.getColumnFormulas(selColPath)
        summaryFormulas.foreach { e=>
          println(s" '${e._1}' : ${e._2.expr}")
        }

      case other => fail("failed to compile formulaList.json")
    }
  }

  //... add evaluation tests
}
