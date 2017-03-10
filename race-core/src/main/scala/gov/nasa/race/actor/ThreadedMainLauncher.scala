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

package gov.nasa.race.actor

import java.lang.reflect.{Method, Modifier}

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.RaceActor
import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.util.ThreadUtils._

/**
 * RaceActor to launch configured Java main class in its own thread, but within
 * the RACE process
 *
 * This should only be used if we know the application is not going to interfere
 * with the ActorSystem, otherwise we should use a ProcessLauncher
 *
 * The main class will *not* use its own classloader, i.e. statics are not
 * separated
 */
class ThreadedMainLauncher (val config: Config) extends RaceActor {

  val optMainMethod: Option[Method] = getMainMethod(config.getString("main-class"))

  val sysProps = config.getOptionalStringList("sys-properties")
  val args = config.getStringListOrElse("args", Seq[String]()).toArray

  val thread = new Thread {
    override def run(): Unit = {
      for (mainMethod <- optMainMethod) {
        for (sp <- sysProps) setSystemProperty(sp)

        try {
          info(s"invoking $mainMethod")
          mainMethod.invoke(null, args)
        } catch {
          case x: Throwable =>
            error(s"error invoking $mainMethod : $x")
            reportThrowable(x)
        }
      }
    }
  }

  def getMainMethod (clsName: String): Option[Method] = {
    try {
      val cls = loadClass(clsName,classOf[Any])
      val mth = cls.getMethod("main", classOf[Array[String]])
      if ((mth.getModifiers & (Modifier.PUBLIC|Modifier.STATIC)) != (Modifier.PUBLIC|Modifier.STATIC)){
        error(s"$clsName.main() not a public static method")
        None
      } else {
        Some(mth)
      }

    } catch {
      case x: ClassNotFoundException =>
        error(s"class not found: $clsName")
        None
      case x: Throwable =>
        error(s"failed to obtain $clsName.main(): $x")
        None
    }
  }

  final val spPattern = """-D(.*)=(.*)""".r

  def setSystemProperty (sp: String) = {
    sp match {
      case spPattern(k,v) => System.setProperty(k,v)
      case _ => error(s"invalid system property spec: $sp")
    }
  }


  override def onStartRaceActor(originator: ActorRef) = {
    if (optMainMethod != None) {
      thread.start()
    } else {
      error("no main method to start")
    }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    stop(thread) // only API that /could/ work
    super.onTerminateRaceActor(originator)
  }
}
