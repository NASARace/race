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

import java.nio.file.Path

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonParseException, UTF8JsonPullParser, UnixPath}
import gov.nasa.race.http.tabdata.UserPermissions.{PermSpec, Perms}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}

object UserPermissions {
  type PermSpec = (String,String)  // regular expressons for provider- and related field- patterns
  type Perms = Seq[PermSpec]

  val _id_        = asc("id")
  val _date_      = asc("date")
  val _columnListId_ = asc("columnlist")
  val _rowListId_ = asc("rowlist")
  val _users_ = asc("users")
  val _columnPattern_ = asc("columnPattern")
  val _rowPattern_ = asc("rowPattern")
}

/**
  * class to hold provider/field patterns for known users that specify which fields a user can edit
  */
case class UserPermissions (id: Path, date: DateTime, columnListId: Path, rowListId: Path, users: immutable.Map[String,Perms])

/**
  * JSON parser for user permissions
  */
class UserPermissionsParser extends UTF8JsonPullParser {
  import UserPermissions._

  def parse (buf: Array[Byte]): Option[UserPermissions] = {
    initialize(buf)

    try {
      ensureNextIsObjectStart()

      val id = UnixPath.intern(readQuotedMember(_id_))
      val date = readDateTimeMember(_date_)

      val columnListId = UnixPath.intern(readQuotedMember(_columnListId_))
      val rowListId = UnixPath.intern(readQuotedMember(_rowListId_))

      val users = readNextObjectMemberInto[Perms,mutable.Map[String,Perms]](_users_,mutable.Map.empty){
        val user = readArrayMemberName().toString
        val perms = readCurrentArrayInto(ArrayBuffer.empty[PermSpec]) {
          readNextObject {
            val providerPattern = readQuotedMember(_columnPattern_).toString
            val fieldPattern = readQuotedMember(_rowPattern_).toString
            (providerPattern, fieldPattern)
          }
        }.toSeq
        (user,perms)
      }.toMap
      Some(UserPermissions(id,date,columnListId,rowListId,users))

    } catch {
      case x: JsonParseException =>
        x.printStackTrace()
        warning(s"malformed userPermissions: ${x.getMessage}, idx=$idx")
        None
    }
  }
}