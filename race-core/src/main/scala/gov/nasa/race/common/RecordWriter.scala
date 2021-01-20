/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.common

import gov.nasa.race._
import gov.nasa.race.uom.DateTime

import scala.collection.mutable

/**
  * something that can write buffer records of type R
  *
  * Note that we don't restrict the input object type since the concrete implementation has to
  * perform matching anyways and could not rely on a covariant type parameter as it appears
  * in contravariant position (update)
  */
trait RecordWriter[+R <: BufferRecord] {
  val rec: R
  val indexMap = new mutable.HashMap[Any,Int]  // map from objects to record indices

  def maxRecords: Int
  def recCount = indexMap.size

  // override this if there is a efficient way to compute/extract the key from a record
  // note this is only used internally and hence we don't have to check the index
  // note also the caller has to guarantee there is at least one matching key
  protected def keyOfRecord(idx: Int): Any = {
    indexMap.foreach { e =>
      if (e._2 == idx) return e._1
    }
    null
  }

  //--- the high level mutators of the record buffer (this is implemented in a generic sub-type such as DenseRecordWriter)
  def update(key: Any, date: DateTime, msg: Any): Result
  def remove(key: Any, date: DateTime): Result

  //--- the low level concrete mutator
  def set(recIndex: Int, msg: Any, isNew: Boolean): Result

  //--- the low level abstract mutators
  def copy (fromIndex: Int, toIndex: Int): Unit = rec(fromIndex).copyTo(toIndex)
  def clear (recIndex: Int): Unit = rec(recIndex).clear

  def store: Result

  //--- override these if there is bookkeeping such as updating headers etc
  def updateDate (date: DateTime): Unit = {}
  def updateRecCount: Unit = {}

  //--- override these if there is file/record locking
  def startUpdate: Boolean = true
  def startUpdateRec(recIndex: Int): Boolean = true
  def endUpdateRec(recIndex: Int): Unit = {}
  def endUpdate: Unit = {}

  // use this in update/remove implementations to ensure proper record locking/unlocking
  def lockRec (recIndex: Int)(f: =>Result): Result = {
    if (startUpdateRec(recIndex)) {
      try {
        f
      } finally {
        endUpdateRec(recIndex)
      }
    } else Failure("could not obtain write access to record")
  }
}

/**
  * a RecordWriter that stores records in a dense buffer
  * This writer re-organizes the record store by moving the last record into the
  * location of a record that is removed
  */
trait DenseRecordWriter[+R <: BufferRecord] extends RecordWriter[R] {

  override def update(key: Any, date: DateTime, o: Any): Result = {
    indexMap.get(key) match {
      case Some(index) =>
        lockRec(index){
          val res = set(index,o,false)
          res.ifSuccess { updateDate(date) }
          res
        }

      case None =>
        if (indexMap.size < maxRecords) {
          val index = indexMap.size
          lockRec(index) {
            val res = set(index, o, true)
            res.ifSuccess {
              indexMap += key -> index
              updateDate(date)
              updateRecCount
            }
            res
          }

        } else Failure("max number of records exceeded")
    }
  }

  override def remove(key: Any, date: DateTime): Result = {
    indexMap.get(key) match {
      case Some(index) =>
        lockRec(index) {
          if (indexMap.size == 1) {
            clear(0)
            indexMap.clear()

          } else {
            val lastIndex = indexMap.size - 1
            val lastKey = keyOfRecord(lastIndex)
            copy(lastIndex, index)

            lockRec(lastIndex) {
              clear(lastIndex)
              indexMap += lastKey -> index
              indexMap -= key
              Success
            }
          }

          updateDate(date)
          updateRecCount
          Success
        }

      case None => Success // since there is nothing to remove this operation succeeds
    }
  }
}
