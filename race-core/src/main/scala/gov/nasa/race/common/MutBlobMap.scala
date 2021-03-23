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
package gov.nasa.race.common

import scala.collection.mutable

/**
  * something that has an array of bytes, provides a hashCode for the byte values and equals other Blobs with the same values
  */
trait Blob {
  def data: Array[Byte]
  def valueHashCode: Int = OATHash.hash(data)

  override def hashCode: Int = valueHashCode  // override this if concrete type can cache

  override def equals (o: Any): Boolean = {
    o match {
      case blob: Blob =>
        val otherData = blob.data
        val ownData = data
        val len = ownData.length
        if (len == otherData.length) {
          var i = 0
          while (i < len) {
            if (ownData(i) != otherData(i)) return false
            i += 1
          }
          true
        } else false
      case _ => false // not a Blob
    }
  }

  override def toString: String = data.map( b=> (0xff & b).toHexString).mkString("[",",","]")
}


/**
  * a mutable HashMap with Blob keys
  *
  * NOTE - instances are not thread safe (we use a cached query object)
  */
class MutBlobMap [T] {

  private class ConstBlob (val data: Array[Byte], override val hashCode: Int) extends Blob {
    def this (bs: Array[Byte]) = this(bs.clone, OATHash.hash(bs))
  }

  /**
    * this object should only be used for queries
    */
  private object varBlob extends Blob {
    private var _data: Array[Byte] = Array.empty
    private var _hashCode: Int = 0

    def data = _data
    override def hashCode: Int = _hashCode

    def data_= (newData: Array[Byte]): Unit = {
      _data = newData
      _hashCode = valueHashCode
    }
  }

  val map = new mutable.HashMap[Blob,T]

  //--- our public interface

  def size: Int = map.size

  def isEmpty: Boolean = map.isEmpty

  def nonEmpty: Boolean = map.nonEmpty

  def foreach ( f: ((Array[Byte], T))=>Unit): Unit = {
    map.foreach ( e=> f( (e._1.data,e._2)) )
  }

  def contains (key: Array[Byte]): Boolean = {
    varBlob.data = key
    map.contains(varBlob)
  }

  def put (key: Array[Byte], value: T): Unit = {
    val k = new ConstBlob(key)
    map.put(k,value)
  }

  def get (key: Array[Byte]): Option[T] = {
    varBlob.data = key
    map.get(varBlob)
  }

  def getOrElse (key: Array[Byte], default: =>T): T = {
    varBlob.data = key
    map.getOrElse(varBlob, default)
  }

  def getOrElseUpdate (key: Array[Byte], default: =>T): T = {
    val k = new ConstBlob(key) // note we can't use varBlob here since it might add a new entry
    map.getOrElseUpdate(k, default)
  }

  def apply (key: Array[Byte]): T = {
    varBlob.data = key
    map.apply(varBlob)
  }

  //... and more to follow
}
