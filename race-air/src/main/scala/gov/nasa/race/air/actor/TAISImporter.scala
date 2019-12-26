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
package gov.nasa.race.air.actor

import gov.nasa.race.air.TRACON

import scala.util.matching.Regex

/**
  * trait to handle conditional TAIS imports, filtered by requested tracons
  */
trait TAISImporter extends SubjectImporter[TRACON]{
  override def topicSubject (topic: Any): Option[TRACON] = {
    topic match {
      case Some(tracon:TRACON) => TRACON.tracons.get(tracon.id)
      case Some(traconId: String) => TRACON.tracons.get(traconId)
      case _ => None
    }
  }
  override def subjectRegex(tracon:TRACON): Option[Regex] = Some(s"<src>${tracon.id}</src>".r)
}
