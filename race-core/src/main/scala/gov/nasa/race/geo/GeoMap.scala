/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.geo

import scala.collection.mutable

/**
  * a map that uses rounded GeoPositions as keys
  * This can be used to map objects with close positions that are considered to be the same
  *
  * note that LongMap is final so we have to use delegation
  * note also that GeoMaps are mutable since we can get quite a lot of updates - thread safety has to be provided by owner
  */
class GeoMap[T]  (val decimals: Int) extends mutable.Map[GeoPosition,T] {
  protected val codec = new WGS84Codec // note that decode is not thread safe
  protected val elems = mutable.LongMap.empty[T]

  @inline final protected def key (pos: GeoPosition): Long = codec.encode( pos.latDeg, pos.lonDeg, decimals)

  def addOneRaw(k: Long, v: T): elems.type = elems.addOne(k,v)
  def removeRaw(k: Long): Option[T] = elems.remove(k)
  def getRaw(k: Long): Option[T] = elems.get(k)
  def getOrElseUpdateRaw(k:Long, defaultValue: =>T): T = elems.getOrElseUpdate(k,defaultValue)
  def foreachRaw[U] ( f: (Long,T)=> U): Unit = elems.foreach( e=> f(e._1, e._2) )

  override def addOne (elem: (GeoPosition,T)): GeoMap.this.type = {
    elems.addOne( key(elem._1), elem._2)
    this
  }

  override def subtractOne (pos: GeoPosition): GeoMap.this.type = {
    elems.subtractOne( key(pos))
    this
  }

  override def get(pos: GeoPosition): Option[T] = {
    elems.get(key(pos))
  }

  // since we have to recompute the rounded GeoPositions this is not very efficient
  override def iterator: Iterator[(GeoPosition, T)] = {
    synchronized {
      val buf = new Array[(GeoPosition,T)](elems.size)
      var i = 0
      elems.foreach { e=>
        codec.decode(e._1)
        buf(i) = GeoPosition.fromDegrees(codec.latDeg, codec.lonDeg) -> e._2
        i += 1
      }
      buf.iterator
    }
  }

  // note that we do not convert back to GeoPosition here since that might introduce a numeric error in deg->rad conversion
  def foreachLatLon[U](f: (Double,Double,T) => U): Unit = {
    elems.foreach { e =>
      codec.decode(e._1, decimals)
      f( codec.latDeg, codec.lonDeg, e._2)
    }
  }

  override def size: Int = elems.size

  override def clear(): Unit = elems.clear()
}
