/*
 * Copyright (c) 2016, United States Government, as represented by the
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

/*
 * Copyright (c) 2016, United States Government, as represented by the 
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

package gov.nasa.race.data

import breeze.collection.mutable.SparseArray

import scala.reflect.ClassTag

/**
 * abstract base for grid data
 *
 * <2do> just a placeholder for now
 */
class Grid[T: ClassTag] (val nCols: Int, val nRows: Int, val nPlanes: Int, defaultValue: T) {
  val data = new SparseArray[T](nRows*nCols*nPlanes, defaultValue)
  private final val pp = nRows * nCols

  def setRange (start: Int, len: Int, v: T): Int = {
    if (v != defaultValue) {
      for (i <- start until start + len) {
        data.update(i, v)
      }
    }
    start+len
  }

  @inline private def outOfRange(n: Int, nMax: Int): Boolean = n < 0 || n >= nMax

  def apply (col: Int, row: Int, plane: Int): T = {
    if (outOfRange(row,nRows) || outOfRange(col, nCols) || outOfRange(plane,nPlanes))
      throw new IndexOutOfBoundsException

    data(plane * pp + row * nCols + col)
  }

  def dump() = {
    for (k <- 0 to nPlanes-1) {
      for (j <- 0 to nRows-1) {
        for (i <- 0 to nCols-1){
          if (i > 0) print(',')
          print( this(i,j,k))
        }
        println()
      }
      println()
    }
  }
}

/**
object Grid {
  def main (args: Array[String]) = {
    val grid = new Grid[Byte](5,5,1,0)
    val a = Array[Byte](0,5, 1,5, 2,5, 3,5, 4,5)

    var i, j=0
    while (j< a.length){
      val v = a(j)
      val rep = a(j+1)
      i = grid.setRange(i, rep, v)
      j = j+2
    }

    grid.dump
  }
}
  **/
