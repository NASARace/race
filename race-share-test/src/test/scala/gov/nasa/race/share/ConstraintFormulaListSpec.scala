/*
 * Copyright (c) 2021, United States Government, as represented by the
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
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, Success}
import org.scalatest.flatspec.AnyFlatSpec

class ConstraintFormulaListSpec extends AnyFlatSpec with RaceSpec with NodeDependentTest {

  val nodeId = "/nodes/node_2"
  val dataDir = "src/resources/data/node_2"

  "a ConstraintFormulaListParser" should "parse a known source" in {

    new ConstraintFormulaListParser().parse(FileUtils.fileContentsAsBytes(dataDir + "/constraintList.json").get) match {
      case Some(cfl) =>
        println("-- parsed constraint formula specs:")
        cfl.formulaSpecs.foreach(println)
        assert(cfl.formulaSpecs.size == 2)

      case None => fail(s"failed to parse")
    }
  }

  "a ConstraintFormulaList" should "compile and evaluate expressions from a known source" in {
    val node = getNode(nodeId, dataDir)
    val cfl = new ConstraintFormulaListParser().parse(FileUtils.fileContentsAsBytes(dataDir + "/constraintList.json").get).get
    val funcLib = new CellFunctionLibrary()

    println(s"-- compiling constraint formulas on node $nodeId")
    cfl.compileWith(node,funcLib) match {
      case Success =>
        println("-- compiled formulas:")
        println("value triggered constraint exprs:")
        cfl.printValueTriggeredFormulaExprs()
        println("time triggered constraint exprs:")
        cfl.printTimeTriggeredFormulaExprs()

        println("-- evaluate value triggerd formula:")

        val date = DateTime.now
        val ctx = new BasicEvalContext( node, date)
        val cv = IntegerCellValue(42,date)
        val cid = "/columns/column_2"
        val rid = "/data/cat_A/field_1"

        println(s"changing cell $cid::$rid to $cv")
        ctx.setCellValue(cid, rid, cv)  // make a triggering change
        assert(ctx.cellValue(cid, rid) eq cv)
        assert(ctx.hasChanges)

        var constraintVerified = false
        cfl.evaluateValueTriggeredFormulas(ctx){ (f,date,res)=>
          println(s"evaluating ${f.id} (${f.info}) -> $res")
          assert(res == false)
          constraintVerified = true
        }
        assert(constraintVerified)

      case Failure(msg) => fail(msg)
    }
  }
}
