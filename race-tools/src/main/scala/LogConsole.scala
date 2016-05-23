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

package gov.nasa.race.tools

import java.io.{FileInputStream, File}
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.net.SimpleSocketServer
import gov.nasa.race.common.ConsoleIO._
import org.slf4j.LoggerFactory;
import scopt.OptionParser

/**
 * wrapper for Logback SimpleSocketServer, to be run in a terminal window
 */
object LogConsole {

  case class CliOpts (logToFile: Boolean=false, port: Int =50505, configFile: File=null)

  def cliParser = {
    new OptionParser[CliOpts]("logconsole") {
      opt[Unit]('f', "file") text("log to tmp/race/log") action{ (_,o) => o.copy(logToFile=true)}
      opt[Int]('p',"port") text("port to listen to") action{(v,o) => o.copy(port=v)}
      arg[File]("<config-file>") text("console configuration file") optional() action { (f, o) =>
        o.copy(configFile = f)
      } validate {
        f => if (f.isFile) success else failure(s"file not found: $f")
      }
    }
  }

  def main (args: Array[String]) = {
    cliParser.parse(args, CliOpts()) match {
      case Some(opts @ CliOpts(logToFile,port,config)) =>
        val is = if (config == null) {
          val res = if (logToFile) "/logback-rolling-server.xml" else "/logback-console-server.xml"
          getClass().getResourceAsStream(res)
        } else new FileInputStream(config)

        val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        val configurator = new JoranConfigurator

        context.reset()
        configurator.setContext(context)
        configurator.doConfigure(is)

        val console = new SimpleSocketServer(context,port)
        console.start

        menu("enter command [9:exit]:\n") {
          case "9" =>  System.exit(0)
        }
      case _ => // nothing, don't start SimpleSocketServer
    }
  }
}
