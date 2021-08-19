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

import akka.http.scaladsl.model.Uri
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.typesafe.config.Config
import com.yubico.webauthn.{AssertionRequest, AssertionResult, FinishAssertionOptions, FinishRegistrationOptions, RegisteredCredential, RegistrationResult, RelyingParty, StartAssertionOptions, StartRegistrationOptions}
import com.yubico.webauthn.data.{AttestationConveyancePreference, AuthenticatorAssertionResponse, AuthenticatorAttachment, AuthenticatorAttestationResponse, AuthenticatorSelectionCriteria, ByteArray, ClientAssertionExtensionOutputs, ClientRegistrationExtensionOutputs, PublicKeyCredential, PublicKeyCredentialCreationOptions, PublicKeyCredentialDescriptor, RelyingPartyIdentity, UserIdentity, UserVerificationRequirement}
import com.yubico.webauthn.exception.{AssertionFailedException, RegistrationFailedException}
import gov.nasa.race.common.ConstUtf8Slice.utf8
import gov.nasa.race.{Failure, ResultValue, Success, SuccessValue}
import gov.nasa.race.common.{BatchedTimeoutMap, ByteSlice, InetAddressMatcher, JsonPullParser, TimeoutSubject}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.NoConfig
import gov.nasa.race.http.AuthMethod.scriptNode
import gov.nasa.race.http.{AuthMethod, AuthResponse, PwUserStore, SocketConnection}
import gov.nasa.race.uom.Time
import scalatags.Text.all._

import java.io.{File, IOException}
import java.net.{InetAddress, InetSocketAddress}
import java.security.SecureRandom
import java.util.Optional
import scala.collection.mutable.Map
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters._

object WebAuthnMethod {
  val AUTH_START = utf8("authStart")
  val AUTH_USER = utf8("authUser")
  val AUTH_CREDENTIALS = utf8("authCredentials")

}
import WebAuthnMethod._

class WebAuthnMethod (config: Config) extends AuthMethod {

  //--- WebAuthn (FIDO2) implementation

  type RegistrationPkc = PublicKeyCredential[AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs]
  type AssertionPkc = PublicKeyCredential[AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs]

  sealed trait PendingRequest extends TimeoutSubject {
    val rp: RelyingParty
    val uid: String
    val clientAddress: InetSocketAddress
    val challenge: ByteArray

    override def hashCode: Int = challenge.hashCode

    override def equals (o: Any): Boolean = {
      o match {
        case other: PendingRequest =>
          (challenge == other.challenge) && (uid == other.uid) && (clientAddress == other.clientAddress)
        case _ => false
      }
    }

    val timeout: Time = requestTimeout

    def timeoutExpired(): Unit = {
      warning(s"pending authentication request from '$uid' on $clientAddress timed out")
    }
  }

  case class PendingRegistrationRequest ( rp: RelyingParty,
                                          uid: String,
                                          clientAddress: InetSocketAddress,
                                          request: PublicKeyCredentialCreationOptions
                                        ) extends PendingRequest {
    val challenge: ByteArray = request.getChallenge
  }

  case class PendingAssertionRequest ( rp: RelyingParty,
                                       uid: String,
                                       clientAddress: InetSocketAddress,
                                       request: AssertionRequest
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

  val pathName = config.getVaultableStringOrElse("user-credentials", "userCredentials.json")
  val credentialStore = new CredentialStore // gets loaded at the end of init if there is an existing file

  // we don't use that for credentials but we might use it to determine if a user can register
  val users = new PwUserStore(config.getVaultableStringOrElse("users", ".users"))

  val regConstraints: AuthConstraints = initAuthConstraints(config.getConfigOrElse("registration", NoConfig),
    InetAddressMatcher.loopbackMatcher, InetAddressMatcher.loopbackMatcher)
  val regRps: Map[InetAddress, RelyingParty] = Map.empty

  val authConstraints: AuthConstraints = initAuthConstraints(config.getConfigOrElse("authentication", NoConfig),
    InetAddressMatcher.allMatcher, InetAddressMatcher.allMatcher)
  val authRps: Map[InetAddress, RelyingParty] = Map.empty

  val requestTimeout: FiniteDuration = config.getFiniteDurationOrElse("timeout", 1.minute)

  // clientAddr -> request : note there is only one at a time from the same port
  // we increase the timeout for user auth to accommodate for network delay
  val pendingRequests = new BatchedTimeoutMap[InetSocketAddress,PendingRequest](requestTimeout + 200.milliseconds)

  loadCredentials()

  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean = {
    credentialStore.isUsernameRegistered(uid)
  }

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
    val builder = AuthenticatorSelectionCriteria.builder

    config.getStringOrElse( "authenticator-attachment", "any") match {
      case "cross"    => builder.authenticatorAttachment( AuthenticatorAttachment.CROSS_PLATFORM)
      case "platform" => builder.authenticatorAttachment( AuthenticatorAttachment.PLATFORM)
      case "any" => // skip
    }

    config.getStringOrElse("user-verification", "any") match {
      case "preferred"   => builder.userVerification( UserVerificationRequirement.PREFERRED)
      case "required"    => builder.userVerification( UserVerificationRequirement.REQUIRED)
      case "discouraged" => builder.userVerification( UserVerificationRequirement.DISCOURAGED)
      case "any" => // skip
    }

    builder.requireResidentKey(config.getBooleanOrElse("resident-key", false))

    builder.build
  }

  def getAttestationConveyancePreference: AttestationConveyancePreference = {
    AttestationConveyancePreference.DIRECT
  }

  def createRp (conn: SocketConnection, credentialStore: CredentialStore, authConstr: AuthConstraints): RelyingParty = {
    val protocol = if (conn.isSSL) "https://" else "http://"
    val rpHostName = authConstr.rpId.getOrElse(conn.localAddress.getHostName)
    val rpAppName = authConstr.rpName.getOrElse(config.getStringOrElse("name", "race"))

    // this is a bit weird - if this is not using SSL the hostname would be rejected by the webauthn lib so we have to
    // add an explicit 'origins' option
    val origins: Set[String] = if (authConstr.origins.nonEmpty) {
      Set.from(authConstr.origins)
    } else {
      Set( protocol + rpHostName)
    }

    val rpId = RelyingPartyIdentity.builder
      .id(rpHostName)
      .name(rpAppName)
      .build

    RelyingParty.builder
      .identity(rpId)
      .credentialRepository(credentialStore)
      .attestationConveyancePreference(getAttestationConveyancePreference)
      .allowOriginSubdomain(true)
      .allowOriginPort(true)
      .origins( origins.asJava)
      .build
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

  override def shutdown(): Unit = {
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
      .timeout(requestTimeout.toMillis)
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
      .timeout(requestTimeout.toMillis)
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

  protected def completeRegistration (pendingRequest: PendingRegistrationRequest, msg: String): AuthResponse = {
    val uid = pendingRequest.uid

    parseRegistrationResponse(msg) match {
      case SuccessValue(pkc) =>  // if the message does not parse we keep the request open
        createRegistrationResult(pendingRequest.rp, pendingRequest.request, pkc) match {
          case SuccessValue(result) =>
            val userIdentity = pendingRequest.request.getUser
            val pkcd = createPublicKeyCredentialDescriptor(pkc)
            val cred = createRegisteredCredential(pkc,result,userIdentity)

            credentialStore.addCredential(uid,pkcd,cred)
            storeCredentials() // we do this upon shutdown to avoid lots of file IO
            info(s"user $uid registered")
            AuthResponse.Accept(uid, s"""{"accept":"$uid"}""")

          case Failure(msg) =>
            warning(s"failed to register user: $msg")
            AuthResponse.Reject(s"""{"reject":"user registration failed"}""")
        }

      case Failure(msg) =>
        warning(s"failed to parse registration pkc: $msg")
        AuthResponse.Reject("""{"reject":"invalid user registration message"}""")
    }
  }

  protected def completeAssertion (pendingRequest: PendingAssertionRequest, msg: String): AuthResponse = {
    val uid = pendingRequest.uid

    parseAssertionResponse(msg) match {
      case SuccessValue(pkc) =>
        createAssertionResult(pendingRequest.rp, pendingRequest.request, pkc) match {
          case SuccessValue(result) =>
            info(s"user $uid authenticated")
            AuthResponse.Accept(uid, s"""{"accept":"$uid"}""")

          case fail: Failure =>
            warning(s"failed to authenticate user: $msg")
            AuthResponse.Reject(s"""{"reject":"user authentication failed"}""")
        }

      case Failure(msg) =>
        warning(s"failed to parse authentication pkc: $msg")
        AuthResponse.Reject("""{"reject":"invalid user authentication message"}""")
    }
  }

  //--- server side of protocol

  override def processJSONAuthMessage (conn: SocketConnection, msgTag: ByteSlice, parser: JsonPullParser): Option[AuthResponse] = {
    val clientAddress = conn.remoteAddress

    msgTag match {
      case AUTH_USER =>
        val uid = parser.quotedValue.toString

        if (!pendingRequests.contains(clientAddress)) {
          if (!isUserRegistered(uid, clientAddress)) { // registration
              if (regConstraints.matches(conn)) {
                val rp = regRps.getOrElseUpdate(conn.localAddress.getAddress, createRp(conn, credentialStore, regConstraints))
                val userIdentity = createUserIdentity(uid)
                val challenge = createRegistrationRequestOptions(rp, userIdentity)
                pendingRequests += (clientAddress -> PendingRegistrationRequest(rp, uid, clientAddress, challenge))
                Some(AuthResponse.Challenge( s"""{"publicKeyCredentialCreationOptions":${serializeToJson(challenge)}}"""))

              } else { // registration constraints not satisfied
                Some(AuthResponse.Reject("""{"reject":"user registration not allowed from this location"}"""))
              }

          } else { // authentication
            if (authConstraints.matches(conn)) {
              val rp = authRps.getOrElseUpdate(conn.localAddress.getAddress, createRp(conn, credentialStore, authConstraints))
              val challenge = createAssertionRequest( rp, uid)
              pendingRequests += (clientAddress -> PendingAssertionRequest( rp, uid, clientAddress, challenge))
              Some(AuthResponse.Challenge( s"""${serializeToJson(challenge)}"""))

            }  else { // authentication constraints not satisfied
              Some(AuthResponse.Reject("""{"reject":"user authentication not allowed from this location"}"""))
            }
          }

        } else { // we already have a pending request from this location
          Some(AuthResponse.Reject("""{"reject":"pending request from this location"}"""))
        }

      case AUTH_CREDENTIALS =>  // client response to challenge - parser is positioned on credential object start
        val pkc = parser.readObjectValueString() // TODO - not ideal, we should use one parser
        pendingRequests.get(clientAddress) match { // do we have a pending request for this client
          case Some(pr:PendingRegistrationRequest) =>
            pendingRequests -= clientAddress
            Some(completeRegistration(pr,pkc))

          case Some(pr:PendingAssertionRequest) =>
            pendingRequests -= clientAddress
            Some(completeAssertion(pr,pkc))

          case None =>
            Some(AuthResponse.Reject("""{"reject":"no pending request from this location"}"""))
        }
    }
  }

  //--- the client side authenticator protocol

  def commonWebAuthnScripting(): String = {
    """
    //--- webauthn utilities

    function base64URLDecode (b64urlstring) {
      return new Uint8Array(atob(b64urlstring.replace(/-/g, '+').replace(/_/g, '/')).split('').map(val => {
        return val.charCodeAt(0);
      }));
    }

    function base64URLEncode (byteArray) {
      return btoa(Array.from(new Uint8Array(byteArray)).map(val => {
        return String.fromCharCode(val);
      }).join('')).replace(/\+/g, '-').replace(/\//g, '_').replace(/\=/g, '');
    }

    function createPkcObject (pkc) {
      return {
        type: pkc.type,
        id: pkc.id,
        response: {
          attestationObject: base64URLEncode(pkc.response.attestationObject),
          clientDataJSON: base64URLEncode(pkc.response.clientDataJSON)
        },
        clientExtensionResults: pkc.getClientExtensionResults()
      };
    }

    function getPkcObject (pkc) {
      return {
        type: pkc.type,
        id: pkc.id,
        response: {
          authenticatorData: base64URLEncode(pkc.response.authenticatorData),
          clientDataJSON: base64URLEncode(pkc.response.clientDataJSON),
          signature: base64URLEncode(pkc.response.signature),
          userHandle: null // make the server look it up through uid/cached request - we don't want to give any assoc in the reply
        },
        clientExtensionResults: pkc.getClientExtensionResults()
      };
    }
    """
  }

  def docRequestScript (requestUrl: Uri, postUrl: String): String = {
    s"""
    ${AuthMethod.commonDocRequestScripting(requestUrl,postUrl)}
    ${commonWebAuthnScripting()}

    function authenticate() {
      let uid = document.getElementById('uid').value;

      getResponse( {authUser: uid})
      .then( response => {
        if (response.publicKeyCredentialCreationOptions) {  // registration
          handleRegistration(response.publicKeyCredentialCreationOptions);
        } else if (response.publicKeyCredentialRequestOptions) {  // authentication
          handleAuthentication(response.publicKeyCredentialRequestOptions);
        }
      });
    }

    function handleRegistration (pkcCreateOptions) {
      //console.log(JSON.stringify(pkcCreateOptions));

      // convert the random strings from base64URL back into Uint8Arrays - the CredentialContainer will otherwise reject
      pkcCreateOptions.user.id = base64URLDecode(pkcCreateOptions.user.id);
      pkcCreateOptions.challenge = base64URLDecode(pkcCreateOptions.challenge);

      navigator.credentials.create( {publicKey: pkcCreateOptions} ).then(  pkc => {
        getResponse( {authCredentials: createPkcObject(pkc)} )
        .then( response => finishAuth(response, '$requestUrl'))
      }, failure => { // navigator.credentials failure
        authAlert("credential creation rejected: " + failure);
      });
    }

    function handleAuthentication (pkcRequestOptions) {
      //console.log(JSON.stringify(pkcRequestOptions));

      // convert the random strings from base64URL back into Uint8Arrays - the CredentialContainer will otherwise reject
      pkcRequestOptions.challenge = base64URLDecode(pkcRequestOptions.challenge);
      for (const c of pkcRequestOptions.allowCredentials) {
        c.id = base64URLDecode(c.id);
      }

      /// TODO - this throws an InvalidStateException on firefox
      navigator.credentials.get( {publicKey: pkcRequestOptions} ).then( pkc => {
        getResponse( {authCredentials:getPkcObject(pkc)} )
        .then( response => finishAuth(response, '$requestUrl'))
      }, failure => {  // navigator.credentials.get failure ??
        //console.trace();
        authAlert("credential request rejected: " + failure);
      });
    }
    """
  }

  def wsRequestScript(): String = {
   s"""
    ${AuthMethod.commonWsRequestScripting()}
    ${commonWebAuthnScripting()}

    function authenticate() {
      const uid = document.getElementById('uid').value;

      if (uid.length == 0){
        authAlert("please enter user");
        return;
      }

      sendAndHandle( {authUser: uid}, function (ws,msg) {
        switch (Object.keys(msg)[0]) {
          case "publicKeyCredentialCreationOptions":
            handleRegistration(msg.publicKeyCredentialCreationOptions);
            return true;

          case "publicKeyCredentialRequestOptions":
            handleAuthentication(msg.publicKeyCredentialRequestOptions);
            return true;
        }
        return false; // not handled
      });
    }

    function handleRegistration (pkcCreateOptions) {
      //console.log(JSON.stringify(pkcCreateOptions));

      // convert the random strings from base64URL back into Uint8Arrays - the CredentialContainer will otherwise reject
      pkcCreateOptions.user.id = base64URLDecode(pkcCreateOptions.user.id);
      pkcCreateOptions.challenge = base64URLDecode(pkcCreateOptions.challenge);

      navigator.credentials.create( {publicKey: pkcCreateOptions} ).then(  pkc => {
        sendAndHandle( {authCredentials: createPkcObject(pkc)}, function (ws,msg) {
          return handleFinalServerResponse(msg);
        })
      });
    }

    function handleAuthentication (pkcRequestOptions) {
      //console.log(JSON.stringify(pkcRequestOptions));

      // convert the random strings from base64URL back into Uint8Arrays - the CredentialContainer will otherwise reject
      pkcRequestOptions.challenge = base64URLDecode(pkcRequestOptions.challenge);
      for (const c of pkcRequestOptions.allowCredentials) {
        c.id = base64URLDecode(c.id);
      }

      /// TODO - this throws an InvalidStateException on firefox
      navigator.credentials.get( {publicKey: pkcRequestOptions} ).then( pkc => {
        sendAndHandle( {authCredentials:getPkcObject(pkc)}, function (ws,msg) {
          return handleFinalServerResponse(msg);
        })
      });
    }
    """
  }

  override def authPage(remoteAddress: InetSocketAddress, requestPrefix: String, requestUrl: Uri, postUrl: String): String = {
    authPage( docRequestScript(requestUrl, postUrl))
  }

  override def authPage(remoteAddress: InetSocketAddress): String = {
    authPage( wsRequestScript())
  }

  def authPage (script: String): String = AuthMethod.userAuthPage(script)
}
