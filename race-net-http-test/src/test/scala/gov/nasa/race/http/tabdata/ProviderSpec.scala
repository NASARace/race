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
  * reg test for Provider data types
  */
class ProviderSpec extends AnyFlatSpec with RaceSpec {

  "a ProviderCatalogParser" should "read ProviderCatalog from JSON source" in {
    val input = FileUtils.fileContentsAsString("race-net-http-test/src/resources/sites/tabdata/data/providerCatalog.json").get

    val parser = new ProviderCatalogParser

    println(s"#-- parsing: $input")

    parser.parse(input.getBytes) match {
      case Some(cat:ProviderCatalog) =>
        println("\n  -> result:")

        println(s"catalog id:  ${cat.id}")
        println(s"catalog date: ${cat.date}")
        println("providers:")
        cat.providers.foreach { e=>
          val (id,field) = e
          println(s"  '$id': $field")
        }

        assert( cat.providers.size == 9)

        val w = new JsonWriter
        w.format(true)
        w.readableDateTime(true)
        cat.serializeTo(w)
        println("\n  -> client JSON:")
        println(w.toJson)

      case _ => fail("failed to parse provider catalog")
    }
  }
}
