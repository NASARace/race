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

import gov.nasa.race.common.JsonWriter
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for Row/RowList/Parser
  */
class RowListSpec extends AnyFlatSpec with RaceSpec {

  "a RowListParser" should "translate a JSON source" in {
    val input = FileUtils.fileContentsAsString("src/resources/data/coordinator/rowList.json").get

    val parser = new RowListParser("/nodes/coordinator")

    println(s"#-- parsing: $input")

    parser.parse(input.getBytes) match {
      case Some(list:RowList) =>
        println("\n  -> result:")

        println(s"list id:  ${list.id}")
        println(s"list date: ${list.date}")
        println("rows:")
        list.rows.foreach { e=>
          val id = e._1
          val row = e._2
          println(s"  '$id': $row")
        }

        assert( list.rows.size == 18)

        val w = new JsonWriter
        w.format(true)
        w.readableDateTime(true)
        list.serializeTo(w)
        println("\n  -> client JSON:")
        println(w.toJson)

      case _ => fail("failed to parse row list")
    }
  }
}
