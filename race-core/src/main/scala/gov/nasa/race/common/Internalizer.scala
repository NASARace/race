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

import scala.collection.mutable

/**
  * an internalizer for arbitrary string data up to a utf-8 length of 64k
  *
  * the purpose of this object is to provide internalization without the need to allocate
  * temporary strings. Note that performance hinges on having few collisions (which we have to resolve
  * based on the map values ourselves). This is why we use a 64bit key computed from the utf-8
  * string representation using MurmurHash64
  *
  * note that it is Ok to do the map entry match for Seq[String] unchecked since this is a private
  * map and this is the only place where it is populated so if it is a Seq we know it holds Strings.
  * Not worth burning another object to wrap the Seq[String] for type safety.
  */
object Internalizer {

  val emptyString = ""
  private val map = new mutable.LongMap[Any](8192)

  def size: Int = map.size
  // todo - maybe add a few other map accessors

  def getMurmurHashed (hash: Long, bs: Array[Byte], off: Int, len: Int): String = synchronized {
    if (len == 0) return emptyString

    val oldSize = map.size
    map.getOrElseUpdate( hash, new String(bs,off,len)) match {
      case s: String =>
        if (map.size == oldSize) { // was already there, check if hash collision
          if (UTFx.utf8Equals(bs,off,len,s)) { // no collision, equal string
            s
          } else { // hash collision
            val newStr = new String(bs,off,len)
            map.put(hash, Seq(s, newStr))
            newStr
          }
        } else { // first lookup, return the added string
          s
        }
      case list: Seq[String] @unchecked =>
        list.foreach { s=>
          if (UTFx.utf8Equals(bs,off,len,s)) return s
        }
        val newStr = new String(bs,off,len)
        map.put(hash, list :+ newStr)
        newStr
    }
  }

  @inline final def get (bs: Array[Byte], off: Int, len: Int): String = getMurmurHashed(MurmurHash64.hashBytes(bs,off,len), bs,off,len)
  @inline final def get (slice: ByteSlice): String = get(slice.data,slice.off,slice.len)
  @inline final def get (bs: Array[Byte]): String = get(bs, 0, bs.length)

  def getMurmurHashed (hash: Long, cs: Array[Char], off: Int, len: Int): String = synchronized {
    if (len == 0) return emptyString

    val oldSize = map.size
    map.getOrElseUpdate( hash, new String(cs,off,len)) match {
      case s: String =>
        if (map.size == oldSize) { // was already there, check if hash collision
          if (UTFx.utf16Equals(cs,off,len,s)) { // no collision, equal string
            s
          } else { // hash collision
            val newStr = new String(cs,off,len)
            map.put(hash, Seq(s, newStr))
            newStr
          }
        } else { // first lookup, return the added string
          s
        }
      case list: Seq[String] @unchecked =>
        list.foreach { s=>
          if (UTFx.utf16Equals(cs,off,len,s)) return s
        }
        val newStr = new String(cs,off,len)
        map.put(hash, list :+ newStr)
        newStr
    }
  }

  @inline def get (cs: Array[Char], off: Int, len: Int): String = getMurmurHashed(MurmurHash64.hashChars(cs,off,len), cs,off,len)

  def get (s: String): String = synchronized {
    val oldSize = map.size
    val key = MurmurHash64.hashString(s)
    map.getOrElseUpdate( key, s) match {
      case ms: String =>
        if (s ne ms) { // was already there, check if hash collision
          if (s == ms) { // no collision, equal string
            ms
          } else { // hash collision
            map.put(key, Seq(ms, s))
            s
          }
        } else { // first lookup, return the added string
          s
        }
      case list: Seq[String] @unchecked =>
        list.foreach { ms=>
          if (ms == s) return ms
        }
        map.put(key, list :+ s)
        s
    }
  }
}

/**
  * specialized string internalizer for ASCII strings not exceeding a length of 8
  *
  * note that for such strings we can hash without collisions and hence do not have to
  * compare data or keep collision lists
  */
object ASCII8Internalizer {

  private val map = new mutable.LongMap[String](16384)

  def size: Int = map.size
  // todo - maybe add a few other map accessors

  def getASCII8Hashed (hash: Long, bs: Array[Byte], off: Int, len: Int): String = {
    if (len > 8) {
      new String(bs,off,len).intern
    } else {
      synchronized {
        var sIntern = map.getOrNull(hash)
        if (sIntern == null) {
          sIntern = new String(bs, off, len)
          map.update(hash, sIntern)
        }
        sIntern
      }
    }
  }

  @inline final def get ( bs: Array[Byte], off: Int, len: Int): String = getASCII8Hashed(ASCII8Hash64.hashBytes(bs,off,len), bs,off,len)
  @inline final def get (slice: ByteSlice): String = get(slice.data,slice.off,slice.len)

  def getASCII8Hashed (hash: Long, cs: Array[Char], off: Int, len: Int): String = {
    if (len > 8) {
      new String(cs,off,len).intern
    } else {
      synchronized {
        var sIntern = map.getOrNull(hash)
        if (sIntern == null) {
          sIntern = new String(cs, off, len)
          map.update(hash, sIntern)
        }
        sIntern
      }
    }
  }

  @inline def get (cs: Array[Char], off: Int, len: Int): String = getASCII8Hashed(ASCII8Hash64.hashChars(cs,off,len), cs,off,len)

  def get (s: String): String = {
    if (s.length > 8) {
      s.intern
    } else {
      val hash = ASCII8Hash64.hashString(s)
      synchronized {
        var sIntern = map.getOrNull(hash)
        if (sIntern == null) {
          sIntern = s
          map.update(hash, sIntern)
        }
        sIntern
      }
    }
  }
}
