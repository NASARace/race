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

package gov.nasa.race.data.filters

import com.typesafe.config.Config
import gov.nasa.race.common.ConfigurableFilter

import scala.xml.{Elem, Text, XML}

/**
 * filter for ASDE-X messages with a given airport
 */
class NasASDEXAirportFilter (val airport: String, val config: Config) extends ConfigurableFilter {

  def this (conf: Config) = this(conf.getString("airport"), conf)

  override def pass (o: Any): Boolean = {
    o match {
      case txt: String => {
        val doc = XML.loadString(txt)

        doc match {
          case item @ Elem( _, "asdexMsg", _, _, _, content @ _ *) => {
            for(e <- doc \ "_") {
              e match {
                case <airport>{Text(facility)}</airport> =>
                  return facility.equals(airport)
                case _ => // don't care
              }
            }
            false
          }
          case _ => false
        }
      }
      case _ => false
    }
  }
}

