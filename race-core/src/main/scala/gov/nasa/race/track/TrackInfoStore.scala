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

package gov.nasa.race.track
import gov.nasa.race.config.NamedConfigurable

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{Map => MMap,HashMap => MHashMap}
import scala.collection.Seq

/**
  * an abstract store for TrackInfos
  */
trait TrackInfoStore {
  def get (id: String): Option[TrackInfo]
  def add (id: String, ti: TrackInfo): Unit
}

/**
  * a non-threadsafe store
  */
class DefaultTrackInfoStore extends TrackInfoStore {
  val trackInfos = MHashMap.empty[String,TrackInfo]

  def get (id: String): Option[TrackInfo] = trackInfos.get(id)
  def add (id: String, ti: TrackInfo) = trackInfos += (id -> ti)
}

/**
  * a threadsafe store that can be used for shared (local) objects
  */
class ConcurrentTrackInfoStore extends TrackInfoStore {
  val trackInfos = TrieMap.empty[String,TrackInfo]

  def get (id: String): Option[TrackInfo] = trackInfos.get(id)
  def add (id: String, ti: TrackInfo) = trackInfos += (id -> ti)
}


/**
  * a configurable object used to initialize and update TrackInfoStores
  */
trait TrackInfoReader extends NamedConfigurable {
  /**
    * initialize reader, providing a optional map for filtering
    * return optional set of TrackInfos we know about (e.g. from config)
    * Called during init.
    * NOTE - the optional map is not thread safe
    */
  def initialize (filterMap: Option[MMap[String,Tracked3dObject]]): Seq[TrackInfo] = Seq.empty

  /**
    * the workhorse, which parses messages for TrackInfo relevant information
    */
  def readMessage (msg: Any): Seq[TrackInfo] = Seq.empty

}
