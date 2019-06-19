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

package gov.nasa.race.main

import scala.collection.Seq
import java.io.File

/**
  * command line options for interactive Main classes running RACE
  * note that we have to deviate from common scopt practice of using (invariant) case classes for options
  * because we want to be able to derive specialized option classes
  */
class MainOpts(title: String) extends CliArgs(title) {
  var delayStart: Boolean=false             // if set, wait for user command to start simulation
  var propertyFile: Option[File] = None     // optional system property definitions (for logging etc.)
  var setHost: Option[String] = None        // set 'race.host' property from network interface name
  var logLevel: Option[String] = None       // simulation log level
  var logConsoleURI: Option[String] = None  // optional URL to send simulation log output to
  var vault: Option[File] = None            // optional config vault (for secret config values)
  var keyStore: Option[File] = None         // optional keystore file for vault encryption
  var alias: Option[String] = Some("vault") // optional alias for stored vault encryption key
  var configFiles: Seq[File] = Seq()        // the config file(s) that define(s) the simulation

  opt0("--delay")("wait for user command to start simulation"){ delayStart = true }
  opt1("-p","--properties")("<pathName>", "optional system properties file to load"){a=> propertyFile=parseExistingFileOption(a)}
  opt1("-v","--vault")("<pathName>", s"config vault file to use (default=${vault.toString})"){a=> vault=parseExistingFileOption(a)}
  opt1("-k","--keystore")("<pathName>",s"keystore file containing config vault key (default=${keyStore.toString})"){a=> keyStore=parseExistingFileOption(a)}
  opt1("-a","--alias")("<aliasName>",s"alias in case keystore is used (default=${alias.get})"){a=> alias=Some(a)}

  opt0("--verbose")("set INFO log level"){ logLevel = Some("info")}
  opt0("--debug")("set DEBUG log level"){ logLevel = Some("debug")}
  opt0("-i","--info")("set INFO log level"){ logLevel = Some("info")}
  opt0("--error")("set ERROR log level"){ logLevel = Some("error")}

  opt1("--set-host")("<interfaceName>","set 'race.host' property from given network interface name (e.g. 'en0')"){a=> setHost=Some(a)}
  opt1("--log-console")("<url>","optional URL to send simulation log output to (e.g. localhost:50505)"){a=> logConsoleURI=Some(a)}

  argN("<config-file>..","universe config file(s)"){as=> configFiles=parseExistingFiles(as)}
}
