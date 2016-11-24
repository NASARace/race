/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.config

import java.io.File
import javax.crypto.Cipher

import com.typesafe.config.{Config, ConfigException, ConfigFactory}
import gov.nasa.race.util.CryptUtils
import gov.nasa.race.util.FileUtils._

/**
 * singleton to manage encrypted config options such as user credentials and URIs
 *
 * We use a single, non-archived, encrypted configuration file (AKA "vault")
 * containing string entries that are under a top level "secret" object, such as
 *
 * {{{
 *   secret {
 *     x.y.password = "12345"
 *   }
 * }}}
 *
 * Vault files can be created with the `gov.nasa.race.tools.CryptConfig` tool.
 *
 * <2do> vaults should be instances so that we can have different ones (system, apps).
 * We should just make sure the system vault can only be set once from main
 */
object ConfigVault {
  final val Root = "secret."

  private var config: Option[Config] = None // decrypted keys, encrypted (string) values
  private var cipher: Option[Cipher] = None

  def none[A](msg: String): Option[A] = {
    println(s"$msg")
    None
  }

  private var initialized = false
  private def checkAndSetInitialized: Unit = synchronized {
    if (initialized) throw new SecurityException("attempt to re-initialize ConfigVault") // no second chances
    initialized = true
  }

  /**
   * the initialization for interactive environments, which will prompt
   * the user for a pass phrase if the cipher is not set
   */
  private def initialize: Boolean = {
    checkAndSetInitialized
    if (cipher.isEmpty) cipher = CryptUtils.getDecryptionCipher

    config = cipher match {
      case None => none("no pass phrase entered, config vault disabled")
      case Some(c) =>
        fileContentsAsBytes(configVault) match {
          case None => none(s"config vault not found: $configVault")
          case Some(bs) =>
            try {
              val conf = ConfigFactory.parseString(new String(c.doFinal(bs)))
              if (conf.hasPath("secret")) {
                Some(conf)
              } else {
                none("vault rejected, does not have a 'secret' element")
              }
            } catch {
              case t: Throwable => none("error decrypting config vault, no vault set")
            }
        }
    }

    config.isDefined
  }

  /**
   * automatic initialization from interactive shells/drivers (e.g. GUIs like
   * RaceConsole or headless Mains driven by web frontends).
   * NOTE - setting vault and cipher should only be supported for known friends from
   * within the same classloader realm. Be aware this is an extension bottleneck - each
   * new client needs to be hard-coded here
   */
  def initialize(vaultFile: File, pass: Array[Char]): Boolean = {
    // TODO - add AccessController.checkPermissions() here

    configVault = vaultFile
    cipher = CryptUtils.getDecryptionCipher(pass)
    initialize
  }

  def initialize(vaultFile: File, ciph: Cipher): Boolean = {
    // TODO - add AccessController.checkPermissions() here

    configVault = vaultFile
    cipher = Some(ciph)
    initialize
  }

  /**
    * the initialization to use if we don't have the file or the key locally, e.g.
    * in a networked, non-interactive mode
    */
  def initialize(launchData: (Config, Array[Byte])) = {
    // TODO - add AccessController.checkPermissions() here

    checkAndSetInitialized
    config = Some(launchData._1)
    cipher = CryptUtils.getDecryptionCipher(launchData._2)
    // no need to initialize, we already got everything
  }

  private var configVault: File = new File(System.getProperty("race.crypt", "conf.crypt"))

  // 2do - this should check for known/registered client classes
  // we should only hand out un-encrypted values to known, signed classes since there is
  // no guarantee the client will not store the value, obtain the config data for another
  // actor, or subclasses an actor that uses encryption

  def getString(key: String): String = {
    // TODO - add AccessController.checkPermissions() here
    config match {
      case None => throw new ConfigException.Missing(key)
      case Some(conf) => CryptUtils.decrypt64(conf.getString(Root + key), cipher.get) // might throw ConfigException.Missing
    }
  }

  def getStringOrElse(key: String, defaultValue: String): String = {
    // TODO - add AccessController.checkPermissions() here
    config match {
      case None => defaultValue
      case Some(conf) =>
        try {
          CryptUtils.decrypt64(conf.getString(Root + key), cipher.get)
        } catch {
          case x: ConfigException.Missing => defaultValue
        }
    }
  }

  def getOptionalString(key: String): Option[String] = {
    // TODO - add AccessController.checkPermissions() here
    config match {
      case None => None
      case Some(conf) =>
        try {
          Some(CryptUtils.decrypt64(conf.getString(Root + key), cipher.get))
        } catch {
          case x: ConfigException.Missing => None
        }
    }
  }
}
