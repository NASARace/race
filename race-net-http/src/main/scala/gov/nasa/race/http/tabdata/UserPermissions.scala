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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonParseException, UTF8JsonPullParser}
import gov.nasa.race.http.tabdata.UserPermissions.{PermSpec, Perms}

import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}

object UserPermissions {
  type PermSpec = (String,String)  // regular expressons for provider- and related field- patterns
  type Perms = Seq[PermSpec]
}

/**
  * class to hold provider/field patterns for known users that specify which fields a user can edit
  */
case class UserPermissions (rev: Int, users: immutable.Map[String,Perms])

/**
  * JSON parser for user permissions
  */
class UserPermissionsParser extends UTF8JsonPullParser {
  private val _rev_ = asc("rev")
  private val _users_ = asc("users")
  private val _providerPattern_ = asc("providerPattern")
  private val _fieldPattern_ = asc("fieldPattern")

  def parse (buf: Array[Byte]): Option[UserPermissions] = {
    initialize(buf)

    try {
      ensureNextIsObjectStart()
      val rev = readUnQuotedMember(_rev_).toInt
      val users = readNextObjectMemberInto[Perms,mutable.Map[String,Perms]](_users_,mutable.Map.empty){
        val user = readArrayMemberName().toString
        val perms = readCurrentArrayInto(ArrayBuffer.empty[PermSpec]) {
          readNextObject {
            val providerPattern = readQuotedMember(_providerPattern_).toString
            val fieldPattern = readQuotedMember(_fieldPattern_).toString
            (providerPattern, fieldPattern)
          }
        }.toSeq
        (user,perms)
      }.toMap
      Some(UserPermissions(rev,users))

    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed userPermissions: ${x.getMessage}, idx=$idx")
        None
    }
  }
}