/*
 * Copyright (c) 2019, United States Government, as represented by the
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

object Slice {
  def apply (bs: Array[Byte], off: Int, len: Int): Slice = new SliceImpl(bs,off,len)
}

/**
  * a sub-range into a byte array representing strings/chars
  *
  * the trait does not allow to modify the internals
  */
trait Slice {
  protected def bs: Array[Byte]

  def offset: Int
  def length: Int

  override def toString: String = new String(bs,offset,length)

  def apply (i: Int): Byte = bs(offset+i)

  def equals(otherBs: Array[Byte], otherOffset: Int, otherLength: Int): Boolean = {
    if (length != otherLength) return false
    var i = offset
    val iEnd = i + length
    var j = otherOffset
    val bsOther = otherBs
    while (i < iEnd) {
      if (bs(i) != bsOther(j)) return false
      i += 1
      j += 1
    }
    true
  }

  @inline def == (other: Slice): Boolean = equals(other.bs, other.offset, other.length)

  def intern: String = {
    if (length > 8) {
      Internalizer.get(bs, offset, length)
    } else {
      ASCII8Internalizer.get(bs,offset,length)
    }
  }
}

/**
  * Slice implementation that allows to change internals
  */
class SliceImpl (var bs: Array[Byte], var offset: Int, var length: Int) extends Slice {
  def this (s: String) = this(s.getBytes,0,s.length)
}

/**
  * a SliceImpl that also computes a 64bit hash value. Can be used for efficient keyword comparison
  *
  * TODO - check if this actually buys much over SliceImpl (runtime type check, cast and super call might nix gains)
  */
class HashedSliceImpl (_bs: Array[Byte], _offset: Int, _length: Int) extends SliceImpl(_bs,_offset,_length) {
  protected var hash: Long = computeHash

  def this (s: String) = this(s.getBytes,0,s.length)

  private def computeHash = if (length <= 9) ASCII8Hash64.hashBytes(bs,offset,length) else MurmurHash64.hashBytes(bs,offset,length)

  def getHash: Long = hash

  def setAndRehash( bsNew: Array[Byte], offNew: Int, lenNew: Int): Unit = {
    bs = bsNew
    offset = offNew
    length = lenNew
    hash = computeHash
  }

  def setAndRehash( offNew: Int, lenNew: Int): Unit = {
    offset = offNew
    length = lenNew
    hash = computeHash
  }

  def equals (otherBs: Array[Byte], otherOffset: Int, otherLength: Int, otherHash: Long): Boolean = {
    if (otherHash != hash) false else equals(otherBs,otherOffset,otherLength)
  }

  override def == (o: Slice): Boolean = {
    o match {
      case other: HashedSliceImpl => equals(other)
      case other: Slice => equals(other)
    }
  }

  override def intern: String = {
    if (length > 8) {
      Internalizer.getMurmurHashed( hash, bs, offset, length)
    } else {
      ASCII8Internalizer.getASCII8Hashed( hash, bs,offset,length)
    }
  }
}


