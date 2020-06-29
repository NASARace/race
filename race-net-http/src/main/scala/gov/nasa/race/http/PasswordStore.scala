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

import java.io._

import de.mkammerer.argon2.Argon2Factory
import gov.nasa.race.util.CryptUtils

import scala.io.BufferedSource
import scala.util.matching.Regex
import scala.collection.Seq

object User {
  final val AnyRole = "*"
  final val UserRole = "user"
  final val AdminRole = "admin"

  final val MaxRejects = 3
}

case class User (uid: String, roles: Seq[String]) {
  def hasRole(r: String) = (r == User.AnyRole) || roles.contains(r)
}

case class UserEntry (hash: String, user: User) {
  private var rejects: Int = 0
  def resetRejects = rejects = 0
  def incRejects = rejects += 1
  def exceededMaxRejects = rejects >= User.MaxRejects
  def remainingLoginAttempts = User.MaxRejects - rejects
}

/**
  * a 1-way hash password store that uses argon2 for the hash function
  *
  * This is essentially a Map[String,userInfo] from keys to hash values. The point is that
  * those hashed values cannot be un-hashed, i.e. the clear text password cannot be retrieved
  * from this map
  *
  * The externalized format consists of ⟨uid⟩:⟨role⟩,..:⟨pw-hash⟩ lines
  *
  * TODO - we might want to add expiration
  */
class PasswordStore (val file: File) {

  protected[this] val argon2 = Argon2Factory.create
  protected[this] var map: Map[String,UserEntry] = Map.empty

  if (file.exists()) load(new FileInputStream(file))

  protected def load (is: InputStream): Unit = {
    val src = new BufferedSource(is)
    for (line: String <- src.getLines()) {
      var i0 = line.indexOf(':')
      if (i0 > 0) {
        val uid = line.substring(0, i0)
        val i1 = line.indexOf(':', i0 + 1)
        if (i1 > 0) {
          val roles = line.substring(i0 + 1, i1).split(',')
          val hash = line.substring(i1 + 1)
          if (hash.length > 0) {
            map = map + (uid -> UserEntry(hash, User(uid, roles)))
          }
        }
      }
    }
    src.close
  }

  def save (os: OutputStream): Boolean = {
    try {
      val w = new PrintWriter(os)
      for (e <- map.toSeq.sortBy(_._1)) {
        w.print(e._1)
        w.print(':')
        val userEntry = e._2
        w.print(userEntry.user.roles.mkString(","))
        w.print(':')
        w.println(userEntry.hash)
      }
      w.close
      true
    } catch {
      case t: Throwable => false
    }
  }

  def saveFile: Boolean = save( new FileOutputStream(file))

  def addUser (uid: String, pw: Array[Char], roles: Seq[String]) = {
    map = map + (uid -> UserEntry(argon2.hash(2, 65536, 1, pw),User(uid,roles)))
  }

  def removeUser(uid: String): Boolean = {
    val n = map.size
    map = map - uid
    map.size < n
  }

  def verify (uid: String, pw: Array[Char]): Option[User] = {
    try {
      map.get(uid) match {
        case Some(userEntry) =>
          if (userEntry.exceededMaxRejects){ // no more attempts
            None
          } else {
            if (argon2.verify(userEntry.hash,pw)) {
              userEntry.resetRejects
              Some(userEntry.user)
            } else { // invalid password
              userEntry.incRejects
              None
            }
          }

        case None => None  // unknown user
      }
    } finally {
      CryptUtils.erase(pw,' ')
    }
  }

  def hasExceededLoginAttempts(uid: String): Boolean = {
    map.get(uid) match {
      case Some(userEntry) => userEntry.exceededMaxRejects
      case None => false
    }
  }

  def remainingLoginAttempts (uid: String): Int = {
    map.get(uid) match {
      case Some(userEntry) => userEntry.remainingLoginAttempts
      case None => -1 // not a known user
    }
  }

  def matching (pattern: Regex): Seq[(String,UserEntry)] = {
    map.view.filterKeys(uid => pattern.findFirstIn(uid).isDefined).toSeq.sortBy(_._1)
  }
}
