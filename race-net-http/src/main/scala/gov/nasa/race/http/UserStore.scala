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

import akka.http.scaladsl.model.headers.HttpCredentials

import java.io._
import akka.parboiled2.util.Base64
import de.mkammerer.argon2.Argon2Factory
import gov.nasa.race.util.{ArrayUtils, CryptUtils}

import scala.io.BufferedSource
import scala.util.matching.Regex
import scala.collection.Seq

/**
  * user record with associated metadata such as roles and (optional) password hash
  *
  * note the client is responsible for capping login attempts and checking user expiration
  */
case class UserEntry (hash: String, user: User)

/**
  * a map for UserEntries that might or might not have password hashes (if there is no pw the hash string is just empty)
  *
  * The externalized format consists of ⟨uid⟩:⟨role⟩,..:⟨pw-hash⟩ lines
  */
abstract class UserStore (val file: File) {

  protected[this] var map: Map[String,UserEntry] = load(file)

  def saveFile: Boolean = store(map, file)

  def load (file: File): Map[String,UserEntry] = {
    var map = Map.empty[String,UserEntry]

    if (file.isFile) {
      val src = new BufferedSource(new FileInputStream(file))
      for (line: String <- src.getLines()) {
        val i0 = line.indexOf(':')
        if (i0 > 0) {
          val uid = line.substring(0, i0)

          val i1 = line.indexOf(':', i0 + 1)
          if (i1 > 0) { // we have a pw hash
            val roles = line.substring(i0 + 1, i1).split(',').map(_.trim)
            val hash = line.substring(i1 + 1)
            if (hash.length > 0) {
              map = map + (uid -> UserEntry(hash, User(uid, roles)))
            }

          } else { // no pw hash
            val roles = line.substring(i0 + 1).split(',').map(_.trim)
            map = map + (uid -> UserEntry("", User(uid,roles)))
          }
        }
      }
      src.close
    }

    map
  }

  def store (map: Map[String,UserEntry], file: File): Boolean = {
    val os = new FileOutputStream(file)
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

  def removeUser(uid: String): Boolean = {
    val n = map.size
    map = map - uid
    map.size < n
  }

  def matching (pattern: Regex): Seq[(String,UserEntry)] = {
    map.view.filterKeys(uid => pattern.findFirstIn(uid).isDefined).toSeq.sortBy(_._1)
  }

  def getUser (uid: String): Option[User] = {
    map.get(uid).map(_.user)
  }

  def isRegisteredUser (uid: String): Boolean = map.contains(uid)

  def hasUserRole (uid: String, role: String): Boolean = {
    map.get(uid) match {
      case Some(e) => e.user.hasRole(role)
      case None => false
    }
  }
}


class PwLessUserStore (f: File) extends UserStore(f) {

  def addUser (uid: String, roles: Seq[String]): Unit = {
    map = map + (uid -> UserEntry("",User(uid,roles)))
  }
}

/**
  * a 1-way hash password store that uses argon2 for the hash function
  *
  * This is essentially a Map[String,userInfo] from keys to hash values. The point is that
  * those hashed values cannot be un-hashed, i.e. the clear text password cannot be retrieved
  * from this map
  */
class PwUserStore (f: File) extends UserStore(f) {

  def this (pathName: String) = this(new File(pathName))

  protected[this] val argon2 = Argon2Factory.create

  def addUser (uid: String, pw: Array[Byte], roles: Seq[String]) = {
    map = map + (uid -> UserEntry(argon2.hash(2, 65536, 1, pw),User(uid,roles)))
  }

  /**
    * check if explicitly provided uid and password match our store
    *
    * NOTE - this will zero-out the pw array before returning
    */
  def getUser(uid: String, pw: Array[Byte]): Option[User] = {
    try {
      map.get(uid) match {
        case Some(userEntry) =>
          if (argon2.verify(userEntry.hash,pw)) {
            Some(userEntry.user)
          } else { // invalid password
            None
          }

        case None => None  // unknown user
      }
    } finally {
      // erase pw as soon as we are done with it
      ArrayUtils.fill(pw,0.toByte)
    }
  }

  /**
    * user lookup/authentication with credentials that are Base64.rfc2045 encoded 'user:password' strings
    * this is to support user authentication via BasicHttpCredentials extra headers (extractCredentials() directive)
    */
  def getUserForCredentials (credentials: HttpCredentials): Option[User] = {
    val uidpw = new String(Base64.rfc2045.decode(credentials.token))
    val idx = uidpw.indexOf(':')
    if (idx > 0){
      val uid = uidpw.substring(0,idx)
      val pw = uidpw.substring(idx+1).getBytes
      getUser(uid,pw)

    } else {
      None  // invalid token
    }
  }
}
