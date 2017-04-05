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
class ChallengeResponseSequence (val byteLength: Int = 64, val expiresAfterMillis: Long=1000*300) {
  private var map: Map[String,(User,Long)] = Map.empty

  val encoder = Base64.getEncoder
  val random = new SecureRandom
  private val buf = new Array[Byte](byteLength)

  def addNewEntry (user: User): String = synchronized {
    random.nextBytes(buf)
    val newToken = new String(encoder.encode(buf))
    map = map + (newToken -> (user,System.currentTimeMillis))
    newToken
  }

  def removeUser (user: User): Boolean = synchronized {
    val n = map.size
    map = map.filter(e => e._2._1 != user)
    map.size < n
  }

  def removeEntry (oldToken: String): Boolean = synchronized {
    val n = map.size
    map = map - oldToken
    map.size < n
  }

  private def isExpired(t: Long): Boolean = (System.currentTimeMillis - t) > expiresAfterMillis

  def replaceExistingEntry(oldToken: String, role: String = User.AnyRole): Either[String,String] = synchronized {
    map.get(oldToken) match {
      case Some((user,t)) =>
        if (!isExpired(t)) {
          if (user.hasRole(role)) {
            map = map - oldToken
            Right(addNewEntry(user))
          } else Left("insufficient user role") // insufficient role
        } else Left("expired session") // expired
      case None => Left("unknown user") // not a known oldToken
    }
  }
}
