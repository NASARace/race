/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.http.webauthn

import java.time.Instant
import java.util
import java.util.Optional
import scala.language.implicitConversions
import scala.jdk.OptionConverters._
import scala.jdk.CollectionConverters._
import scala.collection.mutable.{Map, Set}
import com.yubico.webauthn.data.{ByteArray, PublicKeyCredential, PublicKeyCredentialDescriptor}
import com.yubico.webauthn.{CredentialRepository, RegisteredCredential}


/**
  * our implementation of CredentialRepository
  * this is mostly a mapper between Scala and Java
  *
  * the data model assumes that a user is added when the first associated credential is registered, and
  * the user is removed when the last associated credential is removed
  *
  * Note - ByteArray has generated equals() and hashCode() methods that allow it to be used as Map keys
  *
  * TODO - still needs serialization / load/store
  */
class CredentialStore(pathName: String) extends CredentialRepository {

  val uidToUh: Map[String,ByteArray] = Map.empty
  val uhToUid: Map[ByteArray,String] = Map.empty
  val uidToPkcds: Map[String,Set[PublicKeyCredentialDescriptor]] = Map.empty  // uid -> { pkcd }
  val regCreds: Map[ByteArray,Map[ByteArray,(RegisteredCredential,Instant)]] = Map.empty    // credId -> { uh -> (cred,date) }

  //--- interface query methods

  override def getUserHandleForUsername(username: String): Optional[ByteArray] = synchronized {
    uidToUh.get(username).toJava
  }

  override def getUsernameForUserHandle(userHandle: ByteArray): Optional[String] = synchronized {
    uhToUid.get(userHandle).toJava
  }

  override def getCredentialIdsForUsername(username: String): util.Set[PublicKeyCredentialDescriptor] = synchronized {
    uidToPkcds.get(username) match {
      case Some(pkcd) => pkcd.asJava
      case None => Set.empty[PublicKeyCredentialDescriptor].asJava
    }
  }

  override def lookup(credentialId: ByteArray, userHandle: ByteArray): Optional[RegisteredCredential] = synchronized {
    regCreds.get( credentialId).flatMap( _.get(userHandle).map(_._1)).toJava
  }

  override def lookupAll(credentialId: ByteArray): util.Set[RegisteredCredential] = synchronized {
    regCreds.get(credentialId) match {
      case Some(credMap) => credMap.values.map(_._1).toSet.asJava
      case None => Set.empty[RegisteredCredential].asJava
    }
  }

  //--- our own queries

  def isUsernameRegistered (username: String): Boolean = synchronized {
    uidToUh.contains(username)
  }

  //--- our own mutators

  def addCredential (uid: String, pkcd: PublicKeyCredentialDescriptor, cred: RegisteredCredential): Unit = synchronized {
    val userHandle = cred.getUserHandle
    val credentialId = cred.getCredentialId
    val date = Instant.now()

    uidToUh += uid -> userHandle
    uhToUid += userHandle -> uid
    uidToPkcds.getOrElseUpdate( uid, Set.empty[PublicKeyCredentialDescriptor]) += pkcd
    regCreds.getOrElseUpdate( credentialId, Map.empty[ByteArray,(RegisteredCredential,Instant)]) += userHandle -> (cred,date)
  }

  def removeCredentialId (userHandle: ByteArray, credentialId: ByteArray): Unit = synchronized {
    uhToUid.get(userHandle) match { // is it a known user
      case Some(uid) =>
        regCreds.get(credentialId) match {
          case Some(uhEntries) => // it's a known credentialId
            if ((uhEntries -= userHandle).isEmpty) { // last entry for this credentialId
              regCreds -= credentialId

              //--- check if this was the last credential for the user
              uidToPkcds.filterInPlace( (u,pkcds) => {
                (u != uid) ||
                  (if (pkcds.filterInPlace(_.getId.compareTo(userHandle) != 0).isEmpty) {
                    // last credential for this user, remove uid and userHandle
                    uhToUid -= userHandle
                    uidToUh -= uid
                    false
                  } else true) // there are still credentials for this user
              })
            }
          case None => // unknown credential
        }

      case None => // unknown userHandle
    }
  }

  def removeUser (uid: String): Unit = synchronized {
    uidToUh.get(uid) match {
      case Some(userHandle) =>
        uidToUh -= uid
        uhToUid -= userHandle
        uidToPkcds -= uid
        regCreds.filterInPlace( (_,v)=>  (v -= userHandle).nonEmpty)
      case None => // unknown user
    }
  }

  def removeCredentialsOlderThan (date: Instant): Unit = synchronized {
    regCreds.filterInPlace { (credId64,credEntries)=>
      credEntries.filterInPlace( (uh64,credEntry) => credEntry._2.isAfter(date)).nonEmpty
    }

    uidToPkcds.filterInPlace { (uid, pkcds) =>
      if (pkcds.filterInPlace( p=> regCreds.contains(p.getId)).isEmpty) {
        // last entry for this uid
        uidToUh.get(uid) match {
          case Some(userHandle) =>
            uidToUh -= uid
            uhToUid -= userHandle
          case None => // unknown user
        }
        false
      } else true
    }
  }
}
