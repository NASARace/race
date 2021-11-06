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

import akka.event.Logging
import com.typesafe.config.Config
import gov.nasa.race.common.UnixPath
import gov.nasa.race.common.UnixPath.PathHelper
import gov.nasa.race.core.BusEvent
import gov.nasa.race.test.RaceActorSpec
import gov.nasa.race.test.RaceActorSpec.Continue
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpecLike

import scala.concurrent.duration.DurationInt

/**
  * reg test for TabDataServiceActor
  */
class UpdateActorSpec extends RaceActorSpec with AnyFlatSpecLike {

  val dataDir = "src/resources/data/coordinator"

  val actorConf: Config = createConfig(
    s"""
      | name = "updater"
      | node-list = "$dataDir/nodeList.json"
      | column-list = "$dataDir/columnList.json"
      | row-list = "$dataDir/rowList.json"
      | value-formulas = "$dataDir/formulaList.json"
      | data-dir = "$dataDir"
      | read-from = "/in"
      | write-to = "/out"
      |""".stripMargin
  )

  "an UpdateActor" should "read columnList, rowList.json, formulaList and columnData during creation" in {
    runRaceActorSystem(Logging.InfoLevel) {

      val actor: UpdateActor = addTestActor[UpdateActor]("updater", actorConf)

      printTestActors
      initializeTestActors
      startTestActors(DateTime.now)

      val date = DateTime.now

      val cdc = ColumnDataChange(
        "/columns/column_2",
        "/nodes/node_2",
        date,
        Seq(
          "/data/cat_A/field_1" -> IntegerCellValue(20, date),
          "/data/cat_B/field_2" -> RealCellValue(1000.0,date)
        )
      )

      println(s"--- column data of $actor pre CDC")
      actor.node.printColumnData()

      println(s"\n--- sending CDC $cdc\n") // note this also triggers formulas so we get several node/CDC messages on the bus
      expectBusMsg("/out", 2.seconds, publish("/in", cdc)) {

        case BusEvent(_, node: Node, _) => // this has to come first
          println(s"\n--- got Node message on /out: ")
          node.printColumnData()

          // this should have the values from the CDC
          node.get("/columns/column_2", "/data/cat_B/field_2") match {
            case Some(cv) =>
              cv match {
                case rcv:RealCellValue =>
                  println("checking updated column_2 data..")
                  println(s"\n--- [column_2::cat_B/field_2] = $rcv")
                  assert( rcv.value == 1000.0)
                case _ => fail("wrong cell value type: $cv")
              }
            case None => fail("updated cell value [column_2::cat_B/field_2] not found")
          }

          Continue // we still need to see the ColumnDataChange

        case BusEvent(_, cdcOut: ColumnDataChange, _) => // the delta message has to come second
          println(s"\n--- got CDC on /out: $cdc")
          assert(cdcOut.columnId == cdc.columnId)
          assert(cdcOut.changedValues == cdc.changedValues)

        case BusEvent(_,msg,_) => fail(s"unexpected msg on /out: $msg")
      }


      terminateTestActors
    }
  }
}
