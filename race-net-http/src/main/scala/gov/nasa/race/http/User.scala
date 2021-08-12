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
package gov.nasa.race.http

import scala.collection.Seq

object User {
  final val AnyRole = "*"
  final val UserRole = "user"
  final val AdminRole = "admin"

  final val MaxRejects = 5
}

/**
  * a user id - role aggregate
  */
case class User (uid: String, roles: Seq[String]) {
  def hasRole(r: String) = (r == User.AnyRole) || roles.contains(r)
}

