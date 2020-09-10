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
package gov.nasa.race.http

import java.nio.file.Path

import scala.collection.immutable.ListMap
import gov.nasa.race.common.JsonPullParser
import gov.nasa.race.common.ByteSlice
import gov.nasa.race.common.ConstAsciiSlice.asc
import scala.collection.mutable

/**
  * generic application framework for a hierarchical reporting system with self-replicating data views
  *
  * The general data model is a table with typed, hierarchically indexed rows (and row sets representing the
  * tracked data sets) and columns representing organizations/entities that provide and/or compute values
  * for such data sets
  */
package object tabdata {

  type Cell = (Path,CellValue)

  type AnyRow = Row[_ <: CellValue]
  type AnyCellRef = CellRef[_ <: CellValue]
  type AnyCellExpression = CellExpression[_ <: CellValue]
  type AnyNumExpr = NumExpr[_ <: NumCellValue]
  type AnyConstCellExpression = ConstCellExpression[_ <: CellValue]
  type AnyCellFunction = CellFunction[_ <: CellValue]
  type AnyCellFormula = CellFormula[_ <: CellValue]
  type Formulas = Map[Path,AnyCellFormula]

  trait PathObject {
    val id: Path

    def name: String = id.getFileName.toString
    def parentName: String = id.getParent.toString
  }

  trait AttrsParser extends JsonPullParser {
    def readAttrs (name: ByteSlice): Seq[String] = {
      readOptionalStringArrayMemberInto(name,mutable.ArrayBuffer.empty[String]).map(_.toSeq).getOrElse(Seq.empty[String])
    }
  }
}
