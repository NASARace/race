/*
 * Copyright (c) 2020, United States Government, as represented by the
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

import java.io.FileInputStream
import java.security.{KeyStore, SecureRandom}

import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.util.{CryptUtils, FileUtils}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

/**
  * something that can configure a SSLContext (e.g. to add own trust stores)
  * Use with care!
  */
trait SSLContextUser {

  val config: Config

  def getSSLContext (keyBase: String): Option[SSLContext] = {
    for (
      ksPathName <- config.getOptionalVaultableString(keyBase);
      pw <- config.getOptionalVaultableChars(s"$keyBase-pw")
    ) {

      FileUtils.existingNonEmptyFile(ksPathName) match {
        case Some(ksFile) =>

          val ksType = config.getStringOrElse(s"$keyBase-type", CryptUtils.keyStoreType(ksPathName))
          val ks: KeyStore = KeyStore.getInstance(ksType)
          ks.load(new FileInputStream(ksFile),pw)

          val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
          keyManagerFactory.init(ks, pw)
          CryptUtils.erase(pw, ' ')

          val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
          tmf.init(ks)

          val sslContext: SSLContext = SSLContext.getInstance("TLS")
          sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

          return Some(sslContext)

        case None =>
          throw new ConfigException.Generic(s"invalid  $keyBase")
      }
    }

    None // if there is no/incomplete configuration we just use the default (which is more safe anyways)
  }
}
