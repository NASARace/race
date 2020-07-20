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
import org.scalatest.flatspec.AnyFlatSpec

class FieldSpec extends AnyFlatSpec with RaceSpec {

  //--- test data

  val fieldCatSrc ="""
{
  "title": "Sample Data Set",
  "rev": 1,
  "fields": [
    { "id": "/cat_A",         "type": "integer", "info": "this is category A", "attrs": ["header"], "formula": "(sum `/cat_A/.+`)" },
    { "id": "/cat_A/field_1", "type": "integer", "info": "this is field 1 in category A", "min": 10, "max": 100 },
    { "id": "/cat_A/field_2", "type": "integer", "info": "this is field 2 in category A" },

    { "id": "/cat_B",         "type": "rational", "info": "this is category B", "attrs": ["header", "lock"], "formula": "(sum `/cat_B/.+`)"},
    { "id": "/cat_B/field_1", "type": "rational", "info": "this is field 1 in category B", "min":  100.0 },
    { "id": "/cat_B/field_2", "type": "rational", "info": "this is field 2 in category B" },

    { "id": "/cat_C",         "type": "integer", "info": "this is category C", "attrs": ["header"] },
    { "id": "/cat_C/field_1", "type": "integer", "info": "this is field 1 in category C (total of field_2 changes)", "formula": "(acc /cat_C/field_2)" },
    { "id": "/cat_C/field_2", "type": "integer", "info": "this is field 2 in category C" }
  ]
} """

  "a FieldCatalogParser" should "read FieldCatalog from JSON source" in {
    val parser = new FieldCatalogParser

    println(s"#-- parsing: $fieldCatSrc")

    parser.parse(fieldCatSrc.getBytes) match {
      case Some(cat) =>
        println("\n  -> result:")
        println(cat)
        assert( cat.fields.size == 9)

        val w = new JsonWriter
        cat.serializeTo(w)
        println("\n  -> client JSON:")
        println(w.toJson)

      case None => fail("failed to parse field catalog")
    }
  }
}
