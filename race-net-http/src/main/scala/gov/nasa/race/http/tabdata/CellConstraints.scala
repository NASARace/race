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

/**
  * something that can check if Cell constraints are met
  */
trait CellConstraints {
  def isSatisfied(fv: CellValue): Boolean
}

case class MinConstraints (min: NumCellValue) extends CellConstraints {
  def isSatisfied (fv: CellValue): Boolean = {
    fv match {
      case nfv:NumCellValue => nfv > min
      case _ => false
    }
  }
}

case class MaxConstraints (max: NumCellValue) extends CellConstraints {
  def isSatisfied (fv: CellValue): Boolean = {
    fv match {
      case nfv:NumCellValue => nfv < max
      case _ => false
    }
  }
}

case class IntervalConstraints (min: NumCellValue, max: NumCellValue) extends CellConstraints {
  def isSatisfied (fv: CellValue): Boolean = {
    fv match {
      case nfv:NumCellValue => nfv < max && nfv > min
      case _ => false
    }
  }
}
