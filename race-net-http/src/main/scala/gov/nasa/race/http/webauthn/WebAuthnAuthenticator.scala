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
import com.yubico.webauthn.data._
import com.yubico.webauthn.exception.{AssertionFailedException, RegistrationFailedException}
import com.yubico.webauthn._
import gov.nasa.race.common.InetAddressMatcher
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.http.{AuthClient, Authenticator, SocketConnection}
import gov.nasa.race.{Failure, ResultValue, Success, SuccessValue}

import java.io.{File, IOException}
import java.net.{InetAddress, InetSocketAddress}
import java.security.SecureRandom
import java.util.Optional
import scala.collection.mutable.Map
import scala.jdk.CollectionConverters._

/**
  * Authenticator implementation for W3Cs webauthn standard using (parts of) the Yubico server libraries
  */
class WebAuthnAuthenticator(config: Config = NoConfig) extends Authenticator {

  type RegistrationPkc = PublicKeyCredential[AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs]
  type AssertionPkc = PublicKeyCredential[AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs]

  sealed trait PendingRequest {
    val rp: RelyingParty
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

  case class PendingRegistrationRequest ( rp: RelyingParty,
                                          uid: String,
                                          clientAddress: InetSocketAddress,
                                          request: PublicKeyCredentialCreationOptions,
                                          authClient: AuthClient
                                         ) extends PendingRequest {
    val challenge: ByteArray = request.getChallenge
  }

  case class PendingAssertionRequest ( rp: RelyingParty,
                                       uid: String,
                                       clientAddress: InetSocketAddress,
                                       request: AssertionRequest,
                                       authClient: AuthClient
                                      ) extends PendingRequest {
    val challenge: ByteArray = request.getPublicKeyCredentialRequestOptions.getChallenge
  }

  case class AuthConstraints (rpName: Option[String],
                              rpId: Option[String],
                              origins: Set[String],
                              hostFilter: InetAddressMatcher,
                              clientFilter: InetAddressMatcher) {
    def matches (conn: SocketConnection): Boolean = {
      hostFilter.matchesInetAddress(conn.localAddress.getAddress) && clientFilter.matchesInetAddress(conn.remoteAddress.getAddress)
    }
  }



  //--- our own data

  val random = new SecureRandom()
  val objectMapper: ObjectMapper = initObjectMapper  // watch out - shared resource, needs to be synchronized

  val pathName = config.getStringOrElse("user-credentials", "userCredentials.json")
  val credentialStore = new CredentialStore

  val regConstraints: AuthConstraints = initAuthConstraints(config.getConfigOrElse("registration", NoConfig),
    InetAddressMatcher.loopbackMatcher, InetAddressMatcher.loopbackMatcher)
  val regRps: Map[InetAddress, RelyingParty] = Map.empty

  val authConstraints: AuthConstraints = initAuthConstraints(config.getConfigOrElse("authentication", NoConfig),
    InetAddressMatcher.allMatcher, InetAddressMatcher.allMatcher)
  val authRps: Map[InetAddress, RelyingParty] = Map.empty

  val pendingRequests: Map[InetSocketAddress,PendingRequest] = Map.empty  // clientAddr -> request : note there is only one at a time from the same port

  loadCredentials()


  //--- internal initialization (overridable by subclass)

  def initObjectMapper: ObjectMapper = {
    new ObjectMapper()
      .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
      .setSerializationInclusion(Include.NON_ABSENT)
      .registerModule(new Jdk8Module())
  }

  def initAuthConstraints(subConf: Config, defHostFilter: InetAddressMatcher, defClientFilter: InetAddressMatcher): AuthConstraints = {
    val rpName = subConf.getOptionalString("rp-name")
    val rpId = subConf.getOptionalString("rp-id")
    val origins = subConf.getStringSeq("rp-origins").toSet
    val hostFilter = subConf.getOptionalString("host-filter").map(InetAddressMatcher.apply).getOrElse(defHostFilter)
    val clientFilter = subConf.getOptionalString( "client-filter").map(InetAddressMatcher.apply).getOrElse(defClientFilter)

    AuthConstraints(rpName, rpId, origins, hostFilter, clientFilter)
  }

  def getAuthenticatorSelectionCriteria: AuthenticatorSelectionCriteria = {
    AuthenticatorSelectionCriteria.builder
      .authenticatorAttachment(AuthenticatorAttachment.CROSS_PLATFORM)
      //.authenticatorAttachment(Optional.of(AuthenticatorAttachment.CROSS_PLATFORM))
      .requireResidentKey(false)
      .userVerification(UserVerificationRequirement.PREFERRED)
      .build
  }

  def getAttestationConveyancePreference: AttestationConveyancePreference = {
    AttestationConveyancePreference.DIRECT
  }

  def createRp (conn: SocketConnection, credentialStore: CredentialStore, authConstr: AuthConstraints): RelyingParty = {
    val protocol = if (conn.isSSL) "https://" else "http://"
    val rpHostName = protocol + authConstr.rpId.getOrElse(conn.localAddress.getHostName)
    val rpAppName = authConstr.rpName.getOrElse(config.getString("name"))

    val rpId = RelyingPartyIdentity.builder
      .id(rpHostName)
      .name(rpAppName)
      .build

    val rpb = RelyingParty.builder
      .identity(rpId)
      .credentialRepository(credentialStore)
      .attestationConveyancePreference(getAttestationConveyancePreference)
      .allowOriginSubdomain(true)
      .allowOriginPort(true)

    if (authConstr.origins.nonEmpty) rpb.origins(Set.from(authConstr.origins).asJava)

    rpb.build
  }

  def serializeToJson (o: AnyRef): String = objectMapper.synchronized {
    objectMapper.writeValueAsString(o)
  }

  def loadCredentials(): Unit = {
    val file = new File(pathName)
    if (file.isFile) {
      credentialStore.restore(pathName) match {
        case Success => info(s"credentials restored from: $pathName")
        case Failure(msg) => info(s"failed to restore credentials from $pathName : $msg")
      }
    }
  }

  def storeCredentials(): Unit = {
    credentialStore.store(pathName) match {  // FIXME - authClient independent logging
      case Success => info(s"credentials stored to: $pathName")
      case Failure(msg) => info(s"failed to store credentials to $pathName : $msg")
    }
  }

  override def terminate: Unit = {
    if (credentialStore.hasChanged) storeCredentials()
  }

  //--- registration request

  // note this has to make sure uid cannot be derived from the returned value
  def createUserHandle: ByteArray = {
    val bs = new Array[Byte](32)
    random.nextBytes(bs)
    new ByteArray(bs)
  }

  def createUserIdentity (uid: String): UserIdentity = {
    UserIdentity.builder
      .name(uid)
      .displayName(uid)
      .id(createUserHandle)
      .build
  }

  def createRegistrationRequestOptions (rp: RelyingParty, userIdentity: UserIdentity): PublicKeyCredentialCreationOptions = {
    val opts = StartRegistrationOptions.builder
      .user(userIdentity)
      .authenticatorSelection( getAuthenticatorSelectionCriteria)
      .build

    rp.startRegistration(opts)
  }

  //--- registration response

  def parseRegistrationResponse (jsonString: String): ResultValue[RegistrationPkc] = {
    try {
      SuccessValue(PublicKeyCredential.parseRegistrationResponseJson(jsonString))
    } catch {
      case x: IOException => Failure(x.getMessage)
    }
  }

  def createRegistrationResult (rp: RelyingParty, request: PublicKeyCredentialCreationOptions,pkc: RegistrationPkc): ResultValue[RegistrationResult] = {
    try {
      val opts = FinishRegistrationOptions.builder
        .request(request)
        .response(pkc)
        .build

      SuccessValue(rp.finishRegistration(opts))

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

  def createAssertionRequest (rp: RelyingParty, uid: String): AssertionRequest = {
    rp.startAssertion(StartAssertionOptions.builder
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

  def createAssertionResult (rp: RelyingParty, request: AssertionRequest, pkc: AssertionPkc): ResultValue[AssertionResult] = {
    try {
      val opts = FinishAssertionOptions.builder
        .request(request)
        .response(pkc)
        .build

      SuccessValue(rp.finishAssertion(opts))
    } catch {
      case x: AssertionFailedException => Failure(x.getMessage)
    }
  }

  //--- Authenticator interface

  protected def completeRegistration (pendingRequest: PendingRegistrationRequest, msg: String): Boolean = {
    val authClient = pendingRequest.authClient
    val uid = pendingRequest.uid
    val clientAddress = pendingRequest.clientAddress

    parseRegistrationResponse(msg) match {
      case SuccessValue(pkc) =>  // if the message does not parse we keep the request open
        createRegistrationResult(pendingRequest.rp, pendingRequest.request, pkc) match {
          case SuccessValue(result) =>
            val userIdentity = pendingRequest.request.getUser
            val pkcd = createPublicKeyCredentialDescriptor(pkc)
            val cred = createRegisteredCredential(pkc,result,userIdentity)

            credentialStore.addCredential(uid,pkcd,cred)
            storeCredentials() // we do this upon shutdown to avoid lots of file IO

            authClient.completeRegistration( uid, clientAddress, Success)

          case fail: Failure =>
            authClient.alertUser(clientAddress, "registration failed")
            authClient.completeRegistration( uid, clientAddress, fail)
        }
        true

      case Failure(msg) =>
        warning(s"failed to parse pkc: $msg")
        false // message not consumed
    }
  }

  protected def completeAssertion (pendingRequest: PendingAssertionRequest, msg: String): Boolean = {
    val authClient = pendingRequest.authClient
    val uid = pendingRequest.uid
    val clientAddress = pendingRequest.clientAddress

    parseAssertionResponse(msg) match {
      case SuccessValue(pkc) =>
        createAssertionResult(pendingRequest.rp, pendingRequest.request, pkc) match {
          case SuccessValue(result) =>
            authClient.completeAuthentication(uid, clientAddress, Success)

          case fail: Failure =>
            authClient.completeAuthentication(uid, clientAddress, fail)
        }
        true

      case Failure(msg) => 
        warning(s"failed to parse pkc: $msg")
        false // message not consumed
    }
  }

  override def processClientMessage (clientAddress: InetSocketAddress, msg: String): Boolean = {
    pendingRequests.get(clientAddress) match { // do we have a pending request for this remoteAddress
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

  override def register (uid: String, conn: SocketConnection, authClient: AuthClient): Unit = {
    val clientAddress = conn.remoteAddress

    if (regConstraints.matches(conn)) {
      val rp = regRps.getOrElseUpdate(conn.localAddress.getAddress, createRp(conn,credentialStore,regConstraints))

      if (!isUserRegistered(uid, clientAddress)) {
        if (!pendingRequests.contains(clientAddress)) {
          val userIdentity = createUserIdentity(uid)
          val request = createRegistrationRequestOptions(rp, userIdentity)
          pendingRequests += (clientAddress -> PendingRegistrationRequest(rp, uid, clientAddress, request, authClient))
          authClient.sendRegistrationRequest(clientAddress, serializeToJson(request))
        } else {
          val msg = s"user '$uid' already trying to register"
          authClient.alertUser(clientAddress,msg)
          authClient.completeRegistration(uid, clientAddress, Failure(msg))
        }
      } else {
        val msg = s"user '$uid' already registered"
        authClient.alertUser(clientAddress, msg)
        authClient.completeRegistration(uid, clientAddress, Failure(msg))
      }

    } else {
      authClient.alertUser(clientAddress, "please use designated host for user registration")
      authClient.completeRegistration(uid, clientAddress, Failure(s"not a valid registration host: ${clientAddress.getAddress}"))
    }
  }

  override def authenticate (uid: String, conn: SocketConnection, authClient: AuthClient): Unit = {
    val clientAddress = conn.remoteAddress

    if (authConstraints.matches(conn)) {
      val rp = authRps.getOrElseUpdate(conn.localAddress.getAddress, createRp(conn,credentialStore,authConstraints))

      if (isUserRegistered(uid, clientAddress)) {
        if (!pendingRequests.contains(clientAddress)) {
          val request = createAssertionRequest( rp, uid)
          pendingRequests += (clientAddress -> PendingAssertionRequest( rp, uid, clientAddress, request, authClient))
          authClient.sendAuthenticationRequest(clientAddress, serializeToJson(request))
        } else {
          val msg = s"user '$uid' already trying to log in"
          authClient.alertUser(clientAddress,msg)
          authClient.completeAuthentication(uid, clientAddress, Failure(msg))
        }
      } else {
        val msg = s"user '$uid' not yet registered"
        authClient.alertUser(clientAddress,msg)
        authClient.completeAuthentication(uid, clientAddress, Failure(msg))
      }

    } else {
      authClient.alertUser(clientAddress, "please use valid host for login")
      authClient.completeRegistration(uid, clientAddress, Failure(s"not a valid authentication host: ${clientAddress.getAddress}"))
    }
  }
}
