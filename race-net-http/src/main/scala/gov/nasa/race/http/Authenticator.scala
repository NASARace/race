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
import java.net.InetSocketAddress

trait AuthClient {
  def sendRegistrationRequest (clientAddr: InetSocketAddress, msg: String): Unit
  def completeRegistration (uid: String, clientAddr: InetSocketAddress, result: Result): Unit

  def sendAuthenticationRequest (clientAddr: InetSocketAddress, msg: String): Unit
  def completeAuthentication (uid: String, clientAddr: InetSocketAddress, result: Result): Unit
}

/**
  * interface to be used by HttpServer RouteInfos to abstract configurable user authentication policies such
  * as W3Cs webauthn
  *
  * The register/authenticate methods could also be based on Futures but since we use the Authenticator instance from
  * a multi-threaded environment we want to avoid additional async processing and just use a sync call chain, assuming
  * all of the Authenticator code executes within the using construct (RouteInfo)
  */
trait Authenticator {

  /**
    * process message received from client, return value indicating if the message was consumed or not
    */
  def processClientMessage (clientAddress: InetSocketAddress, msg: String): Boolean

  /**
    * called by the server to test if user is registered
    */
  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean

  /**
    * called by the server to start registration
    */
  def register (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit

  /**
    * called by the server to start authentication
    */
  def authenticate (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit


  /**
    * convenience method that checks if a user is already registered, and if so starts authentication. If not,
    * it starts registration. Successful registration is considered to be authenticated
    */
  def identifyUser (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit = {
    if (isUserRegistered(uid,clientAddress)) {
      authenticate( uid, clientAddress, authClient)
    } else {
      register( uid, clientAddress, authClient)
    }
  }
}


/**
  * no authentication required
  */
object NoAuthenticator extends Authenticator {

  def processClientMessage (clientAddres: InetSocketAddress, msg: String): Boolean = false // we don't consume any client messages

  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean = true

  def register (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit = {
    authClient.completeRegistration(uid,clientAddress,Success)
  }

  def authenticate (uid: String, clientAddress: InetSocketAddress, authClient: AuthClient): Unit = {
    authClient.completeAuthentication(uid,clientAddress,Success)
  }

}