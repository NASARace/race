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

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module

import java.util
import java.util.Optional
import scala.language.implicitConversions
import scala.jdk.OptionConverters._
import scala.jdk.CollectionConverters._
import scala.collection.mutable.{ArrayBuffer, Map, Set}
import com.yubico.webauthn.data.{ByteArray, PublicKeyCredentialDescriptor, PublicKeyCredentialType}
import com.yubico.webauthn.{CredentialRepository, RegisteredCredential}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, Result, Success}

import java.io.File

/**
  * our implementation of CredentialRepository
  * this is mostly a mapper between Scala and Java
  *
  * the data model assumes that a user is added when the first associated credential is registered, and
  * the user is removed when the last associated credential is removed
  *
  * Note - ByteArray has generated equals() and hashCode() methods that allow it to be used as Map keys
  *
  * ?? according to the WebAuhtn standard credential IDs are supposed to be probabilistically unique
  * (see https://www.w3.org/TR/webauthn/#credential-id)
  *    - why do we need to store sets of uid->RegisteredCredential pairs ?
  */
class CredentialStore extends CredentialRepository {

  private var _hasChanged = false

  //--- user - userHandle mapping
  val uidToUh: Map[String,ByteArray] = Map.empty
  val uhToUid: Map[ByteArray,String] = Map.empty  // reverse map

  //--- the stored credentials
  val uidToPkcds: Map[String,Set[PublicKeyCredentialDescriptor]] = Map.empty  // uid -> { pkcd }  (1:N)
  val regCreds: Map[ByteArray,StoredCredential] = Map.empty // credId -> StoredCredential (1:1)

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

  /**
    * note we only allow unique credentialIds and hence return none if the user handle does not match
    */
  override def lookup(credentialId: ByteArray, userHandle: ByteArray): Optional[RegisteredCredential] = synchronized {
    regCreds.get( credentialId) match {
      case Some(sc) =>
        if (sc.getCredential.getUserHandle == userHandle) {
          Optional.of(sc.getCredential) 
        } else {
          Optional.empty()
        }
      case None => Optional.empty()
    }
  }

  /**
    * note we only allow unique credentialIds and hence this will be a single value set
    */
  override def lookupAll(credentialId: ByteArray): util.Set[RegisteredCredential] = synchronized {
    regCreds.get(credentialId) match {
      case Some(sc) => Set(sc.getCredential).asJava
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
    val date = System.currentTimeMillis

    val sc = new StoredCredential(cred,date)

    uidToUh += uid -> userHandle
    uhToUid += userHandle -> uid
    uidToPkcds.getOrElseUpdate( uid, Set.empty[PublicKeyCredentialDescriptor]) += pkcd
    regCreds += (credentialId -> sc)

    _hasChanged = true
  }

  def removeCredentialId (credentialId: ByteArray, userHandle: ByteArray): Unit = synchronized {
    uhToUid.get(userHandle) match { // is it a known user
      case Some(uid) =>
        regCreds.get(credentialId) match {
          case Some(sc) =>
            if (sc.getCredential.getUserHandle == userHandle) {
              regCreds -= credentialId
              _hasChanged = true

              //--- check if this was the last credential for this user
              uidToPkcds.filterInPlace((u, pkcds) => {
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
        regCreds.filterInPlace( (k,v)=> v.getCredential.getUserHandle != userHandle)

        _hasChanged = true
      case None => // unknown user
    }
  }

  def removeCredentialsOlderThan (date: Long): Unit = synchronized {
    val oldSize = regCreds.size
    regCreds.filterInPlace( (k,v)=> v.getDate >= date)
    if (regCreds.size != oldSize) _hasChanged = true

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

  def hasChanged: Boolean = _hasChanged

  //--- snapshot
  def store (pathName: String): Result = {
    val credentials = regCreds.values.toArray
    if (credentials.isEmpty) return Success // nothing to store

    val users = uidToUh.foldLeft(ArrayBuffer.empty[StoredUserEntry])( (acc,e) => acc += new StoredUserEntry(e._1,e._2)).toArray
    val snap = new CredentialStoreSnapshot(users,credentials)

    val mapper = new ObjectMapper()
      .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
      .setSerializationInclusion(Include.NON_ABSENT)
      .registerModule(new Jdk8Module())
      .writerWithDefaultPrettyPrinter()

    val file = File.createTempFile("cred",null)

    try {
      mapper.writeValue(file, snap)
      if (file.isFile) {
        FileUtils.rotate(pathName,5)
        if (file.renameTo(new File(pathName))) Success else Failure(s"failed to rename snapshot $file")

      } else Failure("empty CredentialStore snapshot")
    } catch {
      case t: Throwable => Failure(t.getMessage)
    }
  }

  def restore (pathName: String): Result = {
    val file = new File(pathName)
    if (file.isFile) {
      val mapper = new ObjectMapper()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .setSerializationInclusion(Include.NON_ABSENT)
        .registerModule(new Jdk8Module())

      try {
        val snap = mapper.readValue(file, classOf[CredentialStoreSnapshot])
        if (snap.getCredentials.nonEmpty) {
          regCreds.clear()
          snap.getCredentials.foreach { sc=> 
            regCreds += (sc.getCredential.getCredentialId -> sc) 
          }

          uhToUid.clear()
          uidToUh.clear()
          snap.getUsers.foreach { sc=>
            uidToUh += (sc.getUid -> sc.getUh)
            uhToUid += (sc.getUh -> sc.getUid)
          }

          uidToPkcds.clear()
          uidToUh.foreach { e=>
            val uid = e._1
            val uh = e._2

            regCreds.foreach { e=>
              val cred = e._2.getCredential
              val pkcd =  PublicKeyCredentialDescriptor.builder
                .id(cred.getCredentialId)
                .`type`(PublicKeyCredentialType.PUBLIC_KEY) // TODO - can we have other types?
                .build

              uidToPkcds.getOrElseUpdate(uid, Set.empty[PublicKeyCredentialDescriptor]) += pkcd
            }
          }

          _hasChanged = false
          Success  // snapshot restored

        } else Success // nothing to restore

      } catch {
        case t: Throwable => Failure(t.getMessage)
      }

    } else Success // nothing to restore
  }
}
