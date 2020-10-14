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

import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.common.UnixPath
import gov.nasa.race.common.UnixPath.PathHelper
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpecLike

/**
  * reg test for TabDataServiceActor
  */
class TabDataUpdateActorSpec extends RaceActorSpec with AnyFlatSpecLike {

  val dataDir = "race-net-http-test/src/resources/sites/tabdata/data"

  val integratorConfig: Config = createConfig(
    s"""
      | name = "tdsa"
      | node-id = "/providers/region1/integrator"
      | column-list = "$dataDir/columnList.json"
      | row-list = "$dataDir/rowList.json"
      | formula-list = "$dataDir/formulaList.json"
      | column-data = "$dataDir"
      | read-from = "/in"
      |""".stripMargin
  )

  "a TabDataServiceActor" should "read columnList, rowList, formulaList and columnData during creation" in {
    runRaceActorSystem(Logging.InfoLevel) {

      val node: TabDataUpdateActor = addTestActor[TabDataUpdateActor]("tdsa", integratorConfig)

      printTestActors
      initializeTestActors
      startTestActors(DateTime.now)

      implicit val date = DateTime.now
      val originator = p"/providers/region1/provider_2"
      val cdc = ColumnDataChange(
        originator,
        originator,
        date,
        Seq(
          p"/data/cat_A/field_1" -> LongCellValue(20),
          p"/data/cat_B/field_2" -> DoubleCellValue(1000.0)
        )
      )

      println(s"--- column data of $node pre CDC")
      TabDataUpdateActor.dumpColumnData(node.columnList, node.rowList, node.columnData)

      println(s"\n--- sending CDC $cdc\n")
      publish("/in", cdc)
      sleep(1000)

      println(s"--- column data of $node post CDC")
      TabDataUpdateActor.dumpColumnData(node.columnList, node.rowList, node.columnData)

      node.getCell(p"/providers/region1/provider_2", p"/data/cat_B/field_2") match {
        case Some(cv) =>
          cv match {
            case dcv:DoubleCellValue =>
              println(s"\n--- [provider_2@cat_B/field_2] = $dcv")
              assert( dcv.toDouble == 1000.0)
            case _ => fail("wrong cell value type: $cv")
          }
        case None => fail("updated cell value [provider_2@cat_B/field_2] not found")
      }

      terminateTestActors
    }
  }
}
