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

package gov.nasa.race.tool

import java.io.{File, FileInputStream}

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.net.SimpleSocketServer
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.ConsoleIO._
import org.slf4j.LoggerFactory

/**
 * wrapper for Logback SimpleSocketServer, to be run in a terminal window
 */
object LogConsole {

  class Opts extends CliArgs("usage: logconsole "){
    var logToFile =false
    var port: Int =50505
    var configFile: Option[File] =None

    opt0("-f","--log-to-file")("log to tmp/race/log"){logToFile=true}
    opt1("-nid","--port")("<portNumber>","port to listen on"){a=> port=parseInt(a)}
    arg1("<configFile>","console configuration file"){a=> configFile=parseExistingFileOption(a)}
  }

  def main (args: Array[String]): Unit = {
    val opts = CliArgs(args)(new Opts).getOrElse(return)

    val is = opts.configFile match {
      case Some(configFile) => new FileInputStream(configFile)
      case None =>
        val res = if (opts.logToFile) "/logback-rolling-server.xml" else "/logback-console-server.xml"
        getClass().getResourceAsStream(res)
    }
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val configurator = new JoranConfigurator

    context.reset()
    configurator.setContext(context)
    configurator.doConfigure(is)

    val console = new SimpleSocketServer(context,opts.port)
    console.start

    menu("enter command [9:exit]:\n") {
      case "9" =>  System.exit(0)
    }
  }
}
