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

/**
  * interface to be used by HttpServer RouteInfos to abstract configurable user authentication policies such
  * as W3Cs webauthn
  *
  * The register/authenticate methods could also be based on Futures but since we use the Authenticator instance from
  * a multi-threaded environment we want to avoid additional async processing and just use a sync call chain, assuming
  * all of the Authenticator code executes within the using construct (RouteInfo)
  */
trait Authenticator {

  type CallbackFunction = (String,InetSocketAddress,Result)=>Unit

  /**
    * called from the authenticator to make the server send a message to the client
    */
  def sendToClient (clientAddress: InetSocketAddress, msg: String): Unit

  /**
    * called by the server to let the authenticator process replies from the client
    */
  def receiveFromClient (clientAddress: InetSocketAddress, msg: String): Unit

  /**
    * called by the server to test if user is registered
    */
  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean

  /**
    * called by the server to start registration
    */
  def register (uid: String, clientAddress: InetSocketAddress, callBack: CallbackFunction): Unit

  /**
    * called by the server to test if user is authenticated
    */
  def isUserAuthenticated (uid: String, clientAddress: InetSocketAddress): Boolean

  /**
    * called by the server to start authentication
    */
  def authenticate (uid: String, clientAddress: InetSocketAddress, callBack: CallbackFunction): Unit

  /**
    * convenience method that uses registration as fallback for authentication
    */
  def identifyUser (uid: String, clientAddr: InetSocketAddress, authCallback: CallbackFunction, regCallback: CallbackFunction): Unit = {
    if (!isUserAuthenticated(uid,clientAddr)) {
      if (!isUserRegistered(uid,clientAddr)) {
        register(uid,clientAddr,regCallback)
      } else {
        authenticate(uid,clientAddr,authCallback)
      }
    } else {
      authCallback(uid,clientAddr,Success) // user is already authenticated
    }
  }
}


/**
  * no authentication required
  */
object NoAuthenticator extends Authenticator {

  // nothing to send
  def sendToClient (clientAddress: InetSocketAddress, msg: String): Unit = {}
  def receiveFromClient (clientAddress: InetSocketAddress, msg: String): Unit = {}

  def isUserRegistered (uid: String, clientAddress: InetSocketAddress): Boolean = true
  def register (uid: String, clientAddress: InetSocketAddress, callBack: (String,InetSocketAddress,Result)=>Unit): Unit = callBack(uid,clientAddress,Success)

  def isUserAuthenticated (uid: String, clientAddress: InetSocketAddress): Boolean = true
  def authenticate (uid: String, clientAddress: InetSocketAddress, callBack: (String,InetSocketAddress,Result)=>Unit): Unit = callBack(uid,clientAddress,Success)

}