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
import com.typesafe.config.Config
import com.yubico.webauthn.{AssertionRequest, AssertionResult, FinishAssertionOptions, FinishRegistrationOptions, RegisteredCredential, RegistrationResult, RelyingParty, StartAssertionOptions, StartRegistrationOptions}
import com.yubico.webauthn.data.{AuthenticatorAssertionResponse, AuthenticatorAttestationResponse, ByteArray, ClientAssertionExtensionOutputs, ClientRegistrationExtensionOutputs, PublicKeyCredential, PublicKeyCredentialCreationOptions, PublicKeyCredentialDescriptor, RelyingPartyIdentity, UserIdentity}
import com.yubico.webauthn.exception.{AssertionFailedException, RegistrationFailedException}
import gov.nasa.race.{Failure, ResultValue, Success, SuccessValue}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.http.{AuthClient, Authenticator}

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress}
import java.util.Optional
import scala.collection.mutable.{Map, Set}
import scala.util.Random

/**
  * Authenticator implementation for W3Cs webauthn standard using (parts of) the Yubico server libraries
  */
class WebAuthnAuthenticator(config: Config = NoConfig) extends Authenticator {

  type RegistrationPkc = PublicKeyCredential[AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs]
  type AssertionPkc = PublicKeyCredential[AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs]

  sealed trait PendingRequest {
    val uid: String
    val clientAddress: InetSocketAddress
    val challenge: ByteArray
    val authClient: AuthClient

    override def hashCode: Int = challenge.hashCode

    override def equals (o: Any): Boolean = {
      o match {
        case other: PendingRequest =>
          (challenge == other.challenge) && (uid == other.uid) && (clientAddress == other.clientAddress)
        case _ => false
      }
    }
  }

  case class PendingRegistrationRequest (uid: String, clientAddress: InetSocketAddress,
                                         request: PublicKeyCredentialCreationOptions,
                                         authClient: AuthClient
                                        ) extends PendingRequest {
    val challenge: ByteArray = request.getChallenge
  }

  case class PendingAssertionRequest (uid: String, clientAddress: InetSocketAddress,
                                      request: AssertionRequest,
                                      authClient: AuthClient
                                     ) extends PendingRequest {
    val challenge: ByteArray = request.getPublicKeyCredentialRequestOptions.getChallenge
  }


  val random = new Random()
  val objectMapper: ObjectMapper = initObjectMapper  // watch out - shared resource, needs to be synchronized

  val credentialStore = new CredentialStore(config.getStringOrElse("pathname", "credentials.json"))
  val relyingParty: RelyingParty = initRelyingParty // the server id

  val pendingRequests: Map[InetSocketAddress,PendingRequest] = Map.empty  // clientAddr -> request : note there is only one at a time from the same port


  //--- internal initialization (overridable by subclass)

  def initObjectMapper: ObjectMapper = {
    new ObjectMapper()
      .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
      .setSerializationInclusion(Include.NON_ABSENT)
      .registerModule(new Jdk8Module())
  }

  def initRelyingParty: RelyingParty = {
    val rpId = RelyingPartyIdentity.builder
      .id( config.getStringOrElse("rp-id", InetAddress.getLocalHost.getHostName))
      .name(config.getStringOrElse("rp-name", "share"))
      .build

    RelyingParty.builder
      .identity(rpId)
      .credentialRepository(credentialStore)
      .build
  }

  def serializeToJson (o: AnyRef): String = objectMapper.synchronized {
    objectMapper.writeValueAsString(o)
  }

  //--- registration request

  // note this has to make sure uid cannot be derived from the returned value
  def createUserHandle: ByteArray = new ByteArray( random.nextBytes(64))

  def createUserIdentity (uid: String): UserIdentity = {
    UserIdentity.builder
      .name(uid)
      .displayName(uid)
      .id(createUserHandle)
      .build
  }

  def createRegistrationRequestOptions (userIdentity: UserIdentity): PublicKeyCredentialCreationOptions = {
    val opts = StartRegistrationOptions.builder
      .user(userIdentity)
      .build

    relyingParty.startRegistration(opts)
  }

  //--- registration response

  def parseRegistrationResponse (jsonString: String): ResultValue[RegistrationPkc] = {
    try {
      SuccessValue(PublicKeyCredential.parseRegistrationResponseJson(jsonString))
    } catch {
      case x: IOException => Failure(x.getMessage)
    }
  }

  def createRegistrationResult (request: PublicKeyCredentialCreationOptions,pkc: RegistrationPkc): ResultValue[RegistrationResult] = {
    try {
      val opts = FinishRegistrationOptions.builder
        .request(request)
        .response(pkc)
        .build

      SuccessValue(relyingParty.finishRegistration(opts))
    } catch {
      case x: RegistrationFailedException => Failure(x.getMessage )
    }
  }

  def createRegisteredCredential (response: RegistrationPkc, result: RegistrationResult, userIdentity: UserIdentity): RegisteredCredential = {
    val signatureCount: Long = response.getResponse
      .getParsedAuthenticatorData
      .getSignatureCounter

    RegisteredCredential.builder
      .credentialId(result.getKeyId.getId)
      .userHandle(userIdentity.getId)
      .publicKeyCose(result.getPublicKeyCose)
      .signatureCount(signatureCount)
      .build
  }

  def createPublicKeyCredentialDescriptor (pkc: RegistrationPkc): PublicKeyCredentialDescriptor = {
    PublicKeyCredentialDescriptor.builder
      .id(pkc.getId)
      .`type`(pkc.getType)
      .build
  }

  //--- assertion request

  def createAssertionRequest (uid: String): AssertionRequest = {
    relyingParty.startAssertion(StartAssertionOptions.builder
        .username(Optional.of(uid))
        .build
    )
  }

  //--- assertion response

  def parseAssertionResponse (jsonString: String): ResultValue[AssertionPkc] = {
    try {
      SuccessValue(PublicKeyCredential.parseAssertionResponseJson(jsonString))
    } catch {
      case x: IOException => Failure(x.getMessage)
    }
  }

  def createAssertionResult (request: AssertionRequest, pkc: AssertionPkc): ResultValue[AssertionResult] = {
    try {
      val opts = FinishAssertionOptions.builder
        .request(request)
        .response(pkc)
        .build

      SuccessValue(relyingParty.finishAssertion(opts))
    } catch {
      case x: AssertionFailedException => Failure(x.getMessage)
    }
  }

  //--- Authenticator interface

  protected def completeRegistration (pendingRequest: PendingRegistrationRequest, msg: String): Boolean = {
    val uid = pendingRequest.uid
    val clientAddress = pendingRequest.clientAddress

    parseRegistrationResponse(msg) match {
      case SuccessValue(pkc) =>  // if the message does not parse we keep the request open
        createRegistrationResult(pendingRequest.request, pkc) match {
          case SuccessValue(result) =>
            val userIdentity = pendingRequest.request.getUser
            val pkcd = createPublicKeyCredentialDescriptor(pkc)
            val cred = createRegisteredCredential(pkc,result,userIdentity)

            credentialStore.addCredential(uid,pkcd,cred)
            pendingRequest.authClient.completeRegistration( uid, clientAddress, Success)

          case fail: Failure =>
            pendingRequest.authClient.completeRegistration( uid, clientAddress, fail)
        }
        true

      case Failure(msg) => false // message not consumed
    }
  }

  protected def completeAssertion (pendingRequest: PendingAssertionRequest, msg: String): Boolean = {
    val uid = pendingRequest.uid
    val clientAddress = pendingRequest.clientAddress

    parseAssertionResponse(msg) match {
      case SuccessValue(pkc) =>
        createAssertionResult(pendingRequest.request, pkc) match {
          case SuccessValue(result) =>
            pendingRequest.authClient.completeAuthentication(uid, clientAddress, Success)

          case fail: Failure =>
            pendingRequest.authClient.completeAuthentication(uid, clientAddress, fail)
        }
        true

      case Failure(msg) => false // message not consumed
    }
  }

  override def processClientMessage (clientAddress: InetSocketAddress, msg: String): Boolean = {
    pendingRequests.get(clientAddress) match { // do we have a pending request for this address
      case Some(pendingRequest) =>
        val consumed = pendingRequest match {
          case pr: PendingAssertionRequest => completeAssertion(pr,msg)
          case pr: PendingRegistrationRequest => completeRegistration(pr,msg)
        }
        if (consumed) pendingRequests -= pendingRequest.clientAddress
        consumed

      case None => false
    }
  }

  override def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean = {
    credentialStore.isUsernameRegistered(uid)
  }

  override def register (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit = {
    if (!isUserRegistered(uid,clientAddress)) {
      if (!pendingRequests.contains(clientAddress)) {
        val userIdentity = createUserIdentity(uid)
        val request = createRegistrationRequestOptions(userIdentity)
        pendingRequests += (clientAddress -> PendingRegistrationRequest(uid, clientAddress, request, authClient))
        authClient.sendRegistrationRequest(clientAddress, serializeToJson(request))
      } else {
        authClient.completeRegistration( uid, clientAddress, Failure("user '$uid' already has pending request"))
      }
    } else {
      authClient.completeRegistration( uid, clientAddress, Failure("user '$uid' already registered"))
    }
  }

  override def authenticate (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit = {
    if (isUserRegistered(uid,clientAddress)) {
      if (!pendingRequests.contains(clientAddress)) {
        val request = createAssertionRequest(uid)
        pendingRequests += (clientAddress -> PendingAssertionRequest(uid, clientAddress, request, authClient))
        authClient.sendAuthenticationRequest(clientAddress, serializeToJson(request))
      } else {
        authClient.completeAuthentication( uid, clientAddress, Failure("user '$uid' already has pending request"))
      }
    } else {
      authClient.completeAuthentication( uid, clientAddress, Failure("user '$uid' not yet registered"))
    }
  }
}
