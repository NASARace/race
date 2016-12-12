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

package gov.nasa.race.launcher

import java.io.File
import gov.nasa.race._
import gov.nasa.race.util.ConsoleIO._

/**
  * common console prompts for launching RACE
  *
  * main objective is to provide functions that can be used in for-comprehensions to
  * query all involved parameters, supporting optional values and shortcuts at each level
  */
trait ConsoleLaunchPrompter {
  final val shortcut = "`"  // backticks are not used in any of the valid data items

  //--- input helpers (shortcut etc.)

  def promptWithDefault(msg: String, defaultValue: String): Option[String] = {
    prompt(s"$msg (default=$defaultValue): ") match {
      case Some(`shortcut`) => None // bailout shortcut
      case Some(value) => Some(value)
      case None => Some(defaultValue)
    }
  }

  //--- the prompters

  // these all return Options so that we can use them in for-comprehensions, which
  // means that values that are optional have to be returned as Option[Option[T]]

  def promptOptionalIdentity (idDir: String): Option[Option[File]] = {
    // OS X seems to require dsa whereas Linux uses rsa
    promptExistingFile(
      s"  enter private key pathname for this session (optional, default: $idDir/*_[dr]sa): ", idDir, "*_[dr]sa"
    ) match {
      case None => Some(None) // we take 'none' for an answer
      case Some(file) => Some(Some(file))
    }
  }

  def promptOptionalVaultSpec: Option[Option[(File,Array[Char])]] = {
    promptExistingFile("  enter config vault pathname (optional): ", userDir) match {
      case Some(file) =>
        promptPassword(s"  enter passphrase for $file: ") match {
          case Some(pp) => Some(Some((file,pp)))
          case None => None // vault file without pw is a nogo
        }
      case None => Some(None) // we take 'none' for an answer - no vault
    }
  }
  def promptOptionalVaultFile (configDir: String) = promptExistingFile("  enter vault pathname (optional): ", configDir)

  def promptUser (defaultUser: String) = promptWithDefault("  enter user", defaultUser)
  def promptRemoteUser (defaultUser: String) = promptWithDefault("  enter remote user", defaultUser)
  def promptRemoteHost (defaultHost: String) = promptWithDefault("  enter remote host", defaultHost)
  def promptConfigFile (configDir: String) = promptExistingFile("  enter config pathname: ", configDir)
  def promptSessionLabel (defaultLabel: String) = promptWithDefault("  enter session label: ", defaultLabel)
}
