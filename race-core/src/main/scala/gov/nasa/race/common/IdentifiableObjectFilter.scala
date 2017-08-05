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

package gov.nasa.race.common

import com.typesafe.config.Config
import gov.nasa.race.IdentifiableObject
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableFilter

/**
  * a filter that checks if an IdentifiableAircraft callsign matches any of a given set of regexes
  */
class IdentifiableObjectFilter(val reSpec: Seq[String], val config: Config=null) extends ConfigurableFilter {

  def this (conf: Config) = this(conf.getStringListOrElse("callsigns", Seq.empty), conf)

  val regexes = reSpec.map( _.r)

  override def pass (o: Any): Boolean = {
    if (o != null) {
      o match {
        case obj: IdentifiableObject =>
          val cs = obj.cs
          regexes.exists(_.findFirstIn(cs).isDefined)
        case other => false // don't know
      }
    } else false
  }
}
