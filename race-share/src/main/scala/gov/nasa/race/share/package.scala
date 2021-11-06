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
package gov.nasa.race

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{ByteSlice, JsonPullParser, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  * generic application framework for a hierarchical reporting system with self-replicating data views
  *
  * The general data model is a table with typed, hierarchically indexed rows (and row sets representing the
  * tracked data sets) and columns representing organizations/entities that provide and/or compute values
  * for such data sets
  */
package object share {

  // to improve signature readability
  type ColId = String
  type RowId = String

  type RowDatePair = (String,DateTime)
  type ColumnDatePair = (String,DateTime)

  type CellPair = (String,CellValue[_])
  type CellIdPair = (String,String)

  trait AttrsParser extends JsonPullParser {
    def readAttrs(): Seq[String] = {
      readCurrentStringArrayInto(ArrayBuffer.empty[String]).toSeq
    }
  }

  /**
    * collection of JSON member names used by various JsonPullParser and JsonSerializable types
    *
    * Note these have to be stable names so that they can be used without backquoting in partial functions
    */
  trait JsonConstants {
    val ID = asc("id")
    val INFO = asc("info")
    val DATE = asc("date")
    val ATTRS = asc("attrs")

    val SEND = asc("send")
    val RECEIVE = asc("receive")
    val ROWS = asc("rows")
    val COLUMNS = asc("columns")
    val VALUE = asc("value")
    val VALUES = asc("values")

    val NODE_IDS = asc("nodeIds")
    val IS_ONLINE = asc("isOnline")
  }
}
