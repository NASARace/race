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

import gov.nasa.race.uom.DateTime

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.duration.Duration

case class SessionEntry (uid: String, date: DateTime, remoteAddress: InetSocketAddress)

/**
  * a map that keeps track of (token -> user-id,date) pairs. Tokens can be thought of as single request
  * passwords, i.e. each accepted challenge returns a new one. We also keep track of when the
  * last token was issued so that we can check if it is expired
  *
  * Used e.g. for user auth in RESTful APIs (to obtain Cookie values)
  *
  * Note that we currently just support one login per user id
  */
class SessionTokenStore (val expiresAfter: Duration, val byteLength: Int = 32) {

  private var map: Map[String,SessionEntry] = Map.empty

  val encoder = Base64.getEncoder
  val random = new SecureRandom
  private val buf = new Array[Byte](byteLength)

  def isLoggedIn (uid: String): Boolean = {
    map.foreach { e =>
      if (e._2.uid == uid) { // found user but check if last token has expired (user forgot to log out)
        return DateTime.timeSince(e._2.date).toMillis < expiresAfter.toMillis
      }
    }
    false // no entry for uid
  }

  def addNewEntry (remoteAddress: InetSocketAddress, uid: String): String = synchronized {
    random.nextBytes(buf)
    val newToken = new String(encoder.encode(buf))
    map = map + (newToken -> SessionEntry(uid, DateTime.now, remoteAddress))
    newToken
  }

  def removeUser (uid: String): Boolean = synchronized {
    val n = map.size
    map = map.filter(e => e._2.uid != uid)
    map.size < n
  }

  def removeEntry (oldToken: String): Option[String] = synchronized {
    map.get(oldToken) match {
      case Some(e) =>
        map = map - oldToken
        Some(e.uid)
      case None => None
    }
  }

  private def isExpired (d: DateTime): Boolean = DateTime.timeSince(d).toMillis > expiresAfter.toMillis

  def replaceExistingEntry(oldToken: String): NextTokenResult = synchronized {
    map.get(oldToken) match {
      case Some(SessionEntry(uid,t,remoteAddress)) =>
        if (!isExpired(t)) {
          map = map - oldToken
          NextToken(addNewEntry(remoteAddress, uid))
        } else { // expired
          NextTokenFailure("expired session")
        }

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
  def matchesExistingEntry(token: String): MatchTokenResult = synchronized {
    map.get(token) match {
      case Some(SessionEntry(uid,t,_)) =>
        if (!isExpired(t)) {
          TokenMatched // we don't replace the token
        } else {  // expired
          MatchTokenFailure("expired session")
        }
      case None => MatchTokenFailure("unknown user") // not a known oldToken
    }
  }

  def apply (token: String): Option[SessionEntry] = map.get(token)
}


sealed trait AuthTokenResult
trait TokenFailure extends AuthTokenResult { // can't be a case class since we have to extend
  def reason: String
}

sealed trait NextTokenResult extends AuthTokenResult
case class NextToken(token: String) extends NextTokenResult
case class NextTokenFailure (reason: String) extends TokenFailure with NextTokenResult

sealed trait MatchTokenResult extends AuthTokenResult
case object TokenMatched extends MatchTokenResult
case class MatchTokenFailure (reason: String) extends TokenFailure with MatchTokenResult


