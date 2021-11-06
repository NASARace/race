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
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for UserPermissions
  */
class UserPermissionsSpec extends AnyFlatSpec with RaceSpec {

  "a UserPermissionParser" should "parse a know UserPermission source" in {
    val input = FileUtils.fileContentsAsString("src/resources/data/node_2/userPermissions.json").get
    val parser = new UserPermissionsParser

    println(s"#-- parsing: $input")

    parser.parse(input.getBytes) match {
      case Some(up) =>
        println("\n  -> result:")
        println(up)

        println("\n-- checking permissions for user 'gonzo'")
        val perms = up.getPermissions("gonzo")
        println(perms)
        assert( perms.size == 1)
        assert( perms.head.colRE.matches("/some/column_2"))

      case _ => fail("failed to parse userPermissions")
    }
  }
}
