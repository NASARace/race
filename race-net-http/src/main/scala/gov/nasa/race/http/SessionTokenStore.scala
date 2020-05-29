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
  * passwords, i.e. each accepted challenge returns a new one. We also keep track of when the
  * last token was issued so that we can check if it is expired
  *
  * Used e.g. for user auth in RESTful APIs (to obtain Cookie values)
  *
  * Note that we currently just support one login per user, i.e. the same user cannot be logged in
  * in several roles
  */
class SessionTokenStore(val byteLength: Int = 64, val expiresAfterMillis: Long=1000*300) {
  private var map: Map[String,(User,Long)] = Map.empty

  val encoder = Base64.getEncoder
  val random = new SecureRandom
  private val buf = new Array[Byte](byteLength)

  /**
    * reverse lookup to see if a given uid has a valid token entry, which means the user did
    * a proper login and has not logged out yet.
    * Note this is not efficient since it is O(N)
    */
  def entryForUid (uid: String): Option[(User,Long)] = {
    map.foreach { e =>
      if (e._2._1.uid == uid) return Some(e._2)
    }
    None
  }

  def isLoggedIn (uid: String): Boolean = {
    map.foreach { e =>
      val user = e._2._1
      if (user.uid == uid) { // found user but check if last token has expired (user forgot to log out)
        return ((System.currentTimeMillis - e._2._2)) < expiresAfterMillis
      }
    }
    false // no entry for uid
  }

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

  def removeEntry (oldToken: String): Option[User] = synchronized {
    map.get(oldToken) match {
      case Some(e) =>
        map = map - oldToken
        Some(e._1)
      case None => None
    }
  }

  private def isExpired(t: Long, expirationLimit: Long): Boolean = (System.currentTimeMillis - t) > expirationLimit

  def replaceExistingEntry(oldToken: String, role: String = User.AnyRole): NextTokenResult = synchronized {
    map.get(oldToken) match {
      case Some((user,t)) =>
        if (!isExpired(t,expiresAfterMillis)) {
          if (user.hasRole(role)) {
            map = map - oldToken
            NextToken(addNewEntry(user))
          } else NextTokenFailure("insufficient user role") // insufficient role
        } else NextTokenFailure("expired session") // expired

      case None =>
        NextTokenFailure("unknown user") // not a known oldToken
    }
  }

  /**
    * this only checks if this is the current token value but does not replace the entry
    *
    * Note we support overriding the expiration limit to support cases like automated web socket promotion
    * from response content we serve, i.e. cases where the server knows the follow up request should arrive
    * much quicker than normal interaction and hence warrants a shorter expiration limit
    */
  def matchesExistingEntry(token: String, role: String = User.AnyRole, expirationLimit: Long = expiresAfterMillis): MatchTokenResult = synchronized {
    map.get(token) match {
      case Some((user,t)) =>
        if (!isExpired(t,expirationLimit)) {
          if (user.hasRole(role)) {
            TokenMatched // we don't replace the token
          } else MatchTokenFailure("insufficient user role") // insufficient role
        } else MatchTokenFailure("expired session") // expired
      case None => MatchTokenFailure("unknown user") // not a known oldToken
    }
  }
}


sealed trait AuthTokenResult
sealed class TokenFailure (val reason: String)  extends AuthTokenResult { // can't be a case class since we have to extend
}
object TokenFailure {
  def unapply(o: TokenFailure): Option[String] = Some(o.reason)
}

sealed trait NextTokenResult extends AuthTokenResult
case class NextToken(token: String) extends NextTokenResult
case class NextTokenFailure (override val reason: String) extends TokenFailure(reason) with NextTokenResult

sealed trait MatchTokenResult extends AuthTokenResult
case object TokenMatched extends MatchTokenResult
case class MatchTokenFailure (override val reason: String) extends TokenFailure(reason) with MatchTokenResult


