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

import java.io.File

object UserAuth {
  private var map: Map[File,UserAuth] = Map.empty

  def apply(userFile: File): UserAuth = {
    map.get(userFile) match {
      case Some(userAuth) => userAuth
      case None =>
        if (userFile.isFile) { // add a new entry
          val userAuth = new UserAuth(new PasswordStore(userFile),new ChallengeResponseSequence)
          map = map + (userFile -> userAuth)
          userAuth
        } else {
          throw new RuntimeException(s"user file does not exist: $userFile")
        }
    }
  }
}


/**
  * aggregation of objects used for user authentication
  */
case class UserAuth (pwStore: PasswordStore, crs: ChallengeResponseSequence) {
  @inline def login (uid: String, pw: Array[Char]): Option[String] = pwStore.verify(uid,pw).map(crs.addNewEntry)

  def remainingLoginAttempts(uid: String) = pwStore.remainingLoginAttempts(uid)
}
