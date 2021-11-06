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

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{Glob, JsonParseException, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex

object UserPermissions extends JsonConstants {
  val USER_PERMISSIONS = asc("userPermissions")
  val USER_ENTRIES = asc("userEntries")
  val UID_PATTERN = asc("uidPattern")
  val PERMISSIONS = asc("permissions")
  val COL_PATTERN = asc("columnPattern")
  val ROW_PATTERN = asc("rowPattern")

  def serializePermissions (w: JsonWriter, uid: String, perms: Seq[CellSpec]): String = {
    w.clear().writeObject( _
      .writeObjectMember( "userPermissions") { _
        .writeStringMember("uid", uid)
        .writeArrayMember("permissions"){ w=>
          perms.foreach { p=>
            w.writeObject { _
              .writeStringMember("colPattern", p.colPattern)
              .writeStringMember("rowPattern", p.rowPattern)
            }
          }
        }
      }
    ).toJson
  }
}

case class CellSpec(colPattern: String, rowPattern: String) {
  val colRE = Glob.glob2Regex(colPattern)
  val rowRE = Glob.glob2Regex(rowPattern)
}
case class UserEntry (uidPattern: Regex, permissions: Seq[CellSpec])


/**
  * class to hold column/row patterns for known userEntries that specify which fields a user can edit
  */
case class UserPermissions (id: String, date: DateTime, userEntries: Seq[UserEntry]) {

  def getPermissions (uid: String): Seq[CellSpec] = {
    userEntries.foldRight(Seq.empty[CellSpec]) { (e, acc) =>
      if (e.uidPattern.matches(uid)) (e.permissions ++: acc) else acc
    }
  }

  def isKnownUser (uid: String): Boolean = {
    userEntries.exists( _.uidPattern.matches(uid))
  }
}

/**
  * default UserPermissions that don't have any permissions
  */
object noUserPermissions extends UserPermissions("none", DateTime.UndefinedDateTime, Seq.empty[UserEntry])

/**
  * JSON parser for user permissions
  */
class UserPermissionsParser extends UTF8JsonPullParser {
  import UserPermissions._

  def readCellSpecs(): Seq[CellSpec] = {
    val cellSpecs = ArrayBuffer.empty[CellSpec]

    foreachElementInCurrentArray {
      cellSpecs += readCurrentObject {
        var colPattern: String = null
        var rowPattern: String = null

        foreachMemberInCurrentObject {
          case COL_PATTERN => colPattern = quotedValue.toString
          case ROW_PATTERN => rowPattern = quotedValue.toString
        }
        if (colPattern == null || rowPattern == null) throw exception("missing cell/row pattern in userPermissions")
        CellSpec(colPattern, rowPattern)
      }
    }
    cellSpecs.toSeq
  }

  def readUserEntries(): Seq[UserEntry] = {
    val userEntries = ArrayBuffer.empty[UserEntry]

    foreachElementInCurrentArray {
      userEntries += readCurrentObject {
        var uidPattern: String = null
        var cellSpecs: Seq[CellSpec] = Seq.empty

        foreachMemberInCurrentObject {
          case UID_PATTERN => uidPattern = quotedValue.toString
          case PERMISSIONS => cellSpecs = readCurrentArray( readCellSpecs() )
        }

        if (uidPattern == null) throw exception("missing user id pattern")
        UserEntry(Glob.glob2Regex(uidPattern), cellSpecs)
      }
    }
    userEntries.toSeq
  }

  def readUserPermissions(): UserPermissions = {
    readCurrentObject {
      var id: String = null
      var date: DateTime = DateTime.UndefinedDateTime
      var userEntries: Seq[UserEntry] = Seq.empty

      foreachMemberInCurrentObject {
        case ID => id = quotedValue.toString
        case DATE => date = dateTimeValue
        case USER_ENTRIES => userEntries = readCurrentArray( readUserEntries() )
      }

      if (id == null) throw exception("no userPermissions id")
      UserPermissions(id,date,userEntries)
    }
  }

  def parse (buf: Array[Byte]): Option[UserPermissions] = {
    initialize(buf)
    try {
      readNextObject {
        Some( readNextObjectMember(USER_PERMISSIONS){ readUserPermissions() } )
      }
    } catch {
      case x: JsonParseException =>
        warning(s"malformed userPermissions: ${x.getMessage}")
        None
    }
  }
}