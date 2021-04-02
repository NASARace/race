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
package gov.nasa.race.http

import gov.nasa.race.{Result, Success}
import gov.nasa.race.common.LogWriter
import java.net.InetSocketAddress

/**
  * some object using an Authenticator. This object is responsible for communicating with the client, validating
  * uids and maintaining sessions.
  *
  * Note that we require uids to be validated by the AuthClient *before* starting identification
  */
trait AuthClient {
  def sendRegistrationRequest (clientAddr: InetSocketAddress, msg: String): Unit
  def completeRegistration (uid: String, clientAddr: InetSocketAddress, result: Result): Unit

  def sendAuthenticationRequest (clientAddr: InetSocketAddress, msg: String): Unit
  def completeAuthentication (uid: String, clientAddr: InetSocketAddress, result: Result): Unit

  def alertUser (clientAddr: InetSocketAddress, msg: String): Unit
}

/**
  * interface to be used by HttpServer RouteInfos to abstract configurable user authentication policies such
  * as W3Cs webauthn
  *
  * The register/authenticate methods could also be based on Futures but since we use the Authenticator instance from
  * a multi-threaded environment we want to avoid additional async processing and just use a sync call chain, assuming
  * all of the Authenticator code executes within the using construct (RouteInfo)
  */
trait Authenticator extends LogWriter {

  /**
    * process message received from client, return value indicating if the message was consumed or not
    */
  def processClientMessage (clientAddress: InetSocketAddress, msg: String): Boolean

  /**
    * called by the server to test if user is registered
    */
  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean

  /**
    * called by the server to start registration, after making sure uid is valid for registration
    */
  def register (uid: String, conn: SocketConnection, authClient: AuthClient): Unit

  /**
    * called by the server to start authentication, after making sure uid is valid for authentication
    */
  def authenticate (uid: String, conn: SocketConnection, authClient: AuthClient): Unit

  // override if we have to do cleanup
  def terminate: Unit = {}

  /**
    * convenience method that checks if a user is already registered, and if so starts authentication. If not,
    * it starts registration. Successful registration is considered to be authenticated
    *
    * server has to make sure uid is valid before calling this method
    */
  def identifyUser (uid: String, conn: SocketConnection, authClient: AuthClient): Unit = {
    if (isUserRegistered(uid,conn.remoteAddress)) {
      authenticate( uid, conn, authClient)
    } else {
      register( uid, conn, authClient)
    }
  }
}


/**
  * no authentication required
  */
object NoAuthenticator extends Authenticator {

  def processClientMessage (clientAddres: InetSocketAddress, msg: String): Boolean = false // we don't consume any client messages

  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean = true

  def register (uid: String, conn: SocketConnection, authClient: AuthClient): Unit = {
    authClient.completeRegistration(uid,conn.remoteAddress,Success)
  }

  def authenticate (uid: String, conn: SocketConnection, authClient: AuthClient): Unit = {
    authClient.completeAuthentication(uid,conn.remoteAddress,Success)
  }

}