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

import gov.nasa.race.common.JsonWriter
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for ProviderData
  */
class ProviderDataSpec extends AnyFlatSpec with RaceSpec {

  val resourcePath = "race-net-http-test/src/resources/sites/tabdata/data"

  "a ProviderDataParser" should "read ProviderData from JSON source" in {
    val input = FileUtils.fileContentsAsString(s"$resourcePath/provider_1.json").get
    val fc = new FieldCatalogParser().parse(FileUtils.fileContentsAsString(s"$resourcePath/fieldCatalog.json").get.getBytes).get

    val parser = new ProviderDataParser(fc)
    println(s"#-- parsing: $input")

    parser.parse(input.getBytes) match {
      case Some(d:ProviderData) =>
        println("\n  -> result:")
        println(s"providerId:     ${d.providerId}")
        println(s"fieldCatalogId: ${d.fieldCatalogId}")
        println(s"date:           ${d.date}")
        println("fieldValues:")
        fc.fields.keys.foreach { id=>
          d.fieldValues.get(id) match {
            case Some(v) => println(s"  $id: $v")
            case None =>    println(s"  $id: -")
          }
        }

        val w = new JsonWriter
        w.format(true)
        w.readableDateTime(true)
        d.serializeOrderedTo(w, fc.fields)
        println("\n  -> client JSON:")
        println(w.toJson)

      case _ => fail("failed to parse provider data")
    }
  }
}
