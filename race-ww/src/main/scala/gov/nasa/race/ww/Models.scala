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

package gov.nasa.race.ww

import com.typesafe.config.Config
import gov.nasa.worldwind.ogc.collada.ColladaRoot
import scala.collection.mutable.Map

/**
  *  configurable map of 3d models
  */
class Models (configs: Seq[Config]) {

  var models = configs.foldLeft(Map.empty[String,ColladaRoot])( (map,mc) => {
    val k = mc.getString("key")
    val v = mc.getString("file")
    if (v.endsWith(".dae")) {
      try {
        val r = ColladaRoot.createAndParse(v)
        map + (k -> r)
      } catch {
        case t: Throwable => map // report exception
      }
    } else map // report unsupported model type
  })

  def isEmpty = models.isEmpty
  def nonEmpty = models.nonEmpty

  def get (key: String): Option[ColladaRoot] = models.get(key)
}
