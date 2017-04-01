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

import java.security.SecureRandom
import java.util.Base64

/**
  * a map that keeps track of (token -> User) pairs. Tokens can be thought of as single request
  * passwords, i.e. each accepted challenge returns a new one
  *
  * Used e.g. for user auth in RESTful APIs (to obtain Cookie values)
  */
class ChallengeResponseSequence {
  final val byteLength = 64

  var map: Map[String,User] = Map.empty

  val encoder = Base64.getEncoder
  val random = new SecureRandom
  val buf = new Array[Byte](byteLength)

  def addNewEntry (user: User): String = synchronized {
    random.nextBytes(buf)
    val newToken = new String(encoder.encode(buf))
    map = map + (newToken -> user)
    newToken
  }

  def replaceExistingEntry(oldToken: String, role: String = User.AnyRole): Option[String] = synchronized {
    map.get(oldToken) match {
      case Some(user) =>
        if (user.hasRole(role)) {
          map = map - oldToken
          Some(addNewEntry(user))
        } else None
      case None => None
    }
  }
}
