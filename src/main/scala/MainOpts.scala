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

package gov.nasa.race

import java.io.File

import gov.nasa.race.common.CliArgUtils.{OptionChecker, ParseableOption}
import gov.nasa.race.common.FileUtils._
import scopt.OptionParser

/**
  * command line options for interactive Main classes running RACE
  */
case class MainOpts (delayStart: Boolean=false, // if set, wait for user command to start simulation
                     propertyFile: Option[File] = None, // optional system property definitions (for logging etc.)
                     setHost: Option[String] = None, // set 'race.host' property from network interface name
                     logLevel: Option[String] = None, // simulation log level
                     logConsoleURI: Option[String] = None,  // optional URL to send simulation log output to
                     vault: Option[File] = None,  // optional config vault (for secret config values)
                     keyStore: Option[File] = None, // optional keystore file for vault encryption
                     alias: Option[String] = Some("vault"), // optional alias for stored vault encryption key
                     configFiles: Seq[File] = Seq() // the config file(s) that define(s) the simulation
                    ) extends ParseableOption[MainOpts] {

  override def parser = new OptionParser[MainOpts]("race") with OptionChecker {
    head("race", "1.0") // <2do> get this from SBT configured version
    help("help") abbr ("h") text "print this help and exit"
    opt[Unit]("delay") text "wait for user command to start simulation" action { (_, opts) => opts.copy(delayStart = true) }
    opt[File]('p', "properties") text "optional system properties file to load (default=None)" valueName "<pathname>" optional() action { (v, opts) =>
      opts.copy(propertyFile = Some(v))
    } validate checkFile

    //--- vault decryption
    opt[File]('c',"vault") text "optional config vault to use (default=None)" valueName "<pathname>" optional() action{ (f,o)=>
      o.copy(vault = Some(f)) } validate checkFile
    opt[File]('k',"keystore") text "optional keystore file containing config vault key (default=None)" valueName "<pathname>" optional() action{(f,o) =>
      o.copy(keyStore = Some(f)) } validate checkFile
    opt[String]('a', "alias") text "alias in case keystore is used (default=\"vault\")" optional() action {(v,o) => o.copy(alias=Some(v))}

    //--- logging options
    opt[Unit]('v', "verbose") text "set INFO log level for simulation" action { (_, opts) => opts.copy(logLevel = Some("info")) }
    opt[Unit]('d', "debug") text "set DEBUG log level for simulation" action { (_, opts) => opts.copy(logLevel = Some("debug")) }
    opt[Unit]("info") text "set INFO log level for simulation" action { (_, opts) => opts.copy(logLevel = Some("info")) }
    opt[Unit]("error") text "set ERROR log level for simulation" action { (_, opts) => opts.copy(logLevel = Some("error")) }

    opt[String]("set-host") text "set 'race.host' property from given network interface name (e.g. 'en0')" optional() action { (v, opts) =>
      opts.copy(setHost = Some(v))
    }
    opt[String]("log-console") text "optional URL to send simulation log output to (e.g.: 'localhost:50505')" valueName "<url>" optional() action { (v, opts) =>
      opts.copy(logConsoleURI = Some(v))
    }

    arg[File]("<config-file>...") text "universe config file(s)" unbounded() optional() action { (a, opts) =>
      opts.copy(configFiles = opts.configFiles :+ a)
    } validate checkFile
  }
}
