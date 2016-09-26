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

package gov.nasa.race.remote

import java.io.File

import gov.nasa.race.common.CliArgUtils.ParseableOption
import scopt.OptionParser

/**
  * command line options for RemoteMain
  * note that RemoteMain does not get executed interactively, hence we don't need the full range of MainOpts
  */
case class RemoteMainOpts(host: String = "127.0.0.1", // host through which to reach the RemoteLauncher
                          port: Int = DefaultRemotePort, // port on which we communicate with RemoteLauncher
                          logFile: Option[File] = None // log output to local file
                    ) extends ParseableOption[RemoteMainOpts] {
  def show = {
    println("RemoteMain options:")
    println(s"  launcher-host:       $host")
    println(s"  launcher port:       $port")
    if (logFile.isDefined) println(s"  local logFile:    $logFile")
  }

  override def parser = new OptionParser[RemoteMainOpts]("main") {
    opt[String]("host") text "RemoteLauncher host (name or ip address, default=localhost)" optional() action { (v, opts) => opts.copy(host = v) }
    opt[Int]("port") text s"RemoteLauncher port (default=$DefaultRemotePort)" optional() action { (v, opts) => opts.copy(port = v) }
    opt[File]("logfile") text "local logfile pathname (default=no logfile)" optional() action { (v, opts) => opts.copy(logFile = Some(v)) }
  }

}
