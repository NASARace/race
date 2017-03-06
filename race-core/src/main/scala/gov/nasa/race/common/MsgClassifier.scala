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

import gov.nasa.race.config.ConfigUtils._
import com.typesafe.config.Config
import gov.nasa.race.util.StringUtils

import scala.util.matching.Regex
import scala.collection.JavaConverters._


/**
  * a list of (name, regexes) pairs to classify text messages
  */
object MsgClassifier {
  def getClassifiers (config: Config): Seq[MsgClassifier] = {
    config.getOptionalConfigList("classifiers").reverse.foldLeft(List.empty[MsgClassifier]) { (list, conf) =>
      val name = conf.getString("name")
      val patterns = conf.getStringList("patterns").asScala.map(new Regex(_))
      MsgClassifier(name, patterns) :: list
    }
  }

  def classify (msg: String, classifiers: Seq[MsgClassifier]): Option[MsgClassifier] = {
    classifiers.foreach(c => if (StringUtils.matchesAll(msg,c.patterns)) return Some(c) )
    None
  }
}

case class MsgClassifier (name: String, patterns: Seq[Regex])
