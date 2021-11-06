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

import java.nio.file.Path
import gov.nasa.race.common.{JsonWriter, StringJsonPullParser}
import gov.nasa.race.common.UnixPath.PathHelper
import gov.nasa.race.share.Row.{integerListRow, integerRow, realRow}
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.immutable.ListMap

/**
  * reg test for ProviderData
  */
class ColumnDataSpec extends AnyFlatSpec with RaceSpec {

  val resourcePath = "src/resources/data/coordinator"
  val date = DateTime.parseYMDT("2020-06-28T12:00:02.000")
  val rl = "/data"

  val rowList = RowList (rl, date,

    integerRow(s"$rl/cat_A"),
    integerRow(s"$rl/cat_A/field_1"),
    integerRow(s"$rl/cat_A/field_2"),
    integerRow(s"$rl/cat_A/field_3"),
    integerListRow(s"$rl/cat_A/field_4"),

    realRow(s"$rl/cat_B"),
    realRow(s"$rl/cat_B/field_1"),
    realRow(s"$rl/cat_B/field_2"),

    integerRow(s"$rl/cat_C"),
    integerRow(s"$rl/cat_C/field_1"),
    integerRow(s"$rl/cat_C/field_2"),
    integerRow(s"$rl/cat_C/field_3"),
    integerRow(s"$rl/cat_C/field_4")
  )

  "a ColumnDataParser" should "read ColumnData from JSON source" in {
    // make sure that is a CD with mixed default and explicit CV dates
    val input = FileUtils.fileContentsAsString(s"$resourcePath/column_2.json").get

    val parser = new ColumnDataParser(rowList)
    println(s"#-- parsing: $input")

    parser.parse(input.getBytes) match {
      case Some(d:ColumnData) =>
        println("\n  -> result:")

        val w = new JsonWriter
        w.format(true)
        w.readableDateTime(true)
        d.serializeOrderedTo(w, rowList)
        println("\n  -> client JSON:")
        println(w.toJson)

      case _ => fail("failed to parse provider data")
    }
  }

  "a ColumnDataChangeParser" should "read known ColumnDataChange messages" in {
    val input =
      """ {
            "columnDataChange": {
              "columnId": "/columns/c1",
              "changeNodeId": "/nodes/originator",
              "date": "2020-06-28T12:00:02.000",
              "changedValues": {
                "/data/cat_A/field_1": 42,
                "/data/cat_A/field_4": [ 4200, 4300 ],
                "/data/cat_B/field_1": 42.42
              }
            }
         }
      """

    class CDCParser (val rowList: RowList) extends StringJsonPullParser with ColumnDataChangeParser

    println(s"#-- parsing: $input")

    val parser = new CDCParser(rowList)
    parser.initialize(input)
    parser.parse() match {
      case Some(cdc) =>
        println(s"-- parsed:\n   $cdc")
        assert(cdc.columnId == "/columns/c1")
        assert(cdc.changeNodeId == "/nodes/originator")
        assert(cdc.changedValues.size == 3)

        val w = new JsonWriter
        w.format(true)
        w.readableDateTime(true)

        val cdcMsg = w.toJson(cdc)
        println("\n-- generated columnDataChange message: ")
        println(cdcMsg)

        println("\nOk.")

      case None => fail("failed to parse CDC source")
    }
  }
}
