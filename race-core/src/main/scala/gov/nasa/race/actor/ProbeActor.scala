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

import java.io.{FileOutputStream, PrintStream}
import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.config.ConfigurableTranslator
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createOutputStream}
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.core.BusEvent
import gov.nasa.race.core.{ChannelTopicSubscriber, SubscribingRaceActor}
import gov.nasa.race.util.{ConsoleIO, SoundUtils}


/**
 * utility actor that prints messages received on its configured 'read-from' channels to stdout
 * (i.e. it prints in the terminal the process is running in, which might be a satellite)
 */
class ProbeActor (val config: Config) extends ChannelTopicSubscriber {
  val prefix = config.getOptionalString("prefix")
  val translator: Option[ConfigurableTranslator] = config.getOptionalConfig("translator") flatMap createTranslator
  val alert: Boolean = config.getBooleanOrElse("alert", false)

  val pathName = config.getOptionalString("pathname")  // optional pathname to log to (in addition to console output)
  val fos = pathName.map(pn => new PrintStream(createOutputStream(config,pn)))


  override def handleMessage = {
    case BusEvent(sel,msg,_) => report(sel,msg)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    ifSome(fos) {_.close}
    super.onTerminateRaceActor(originator)
  }

  def createTranslator(transConf: Config) = {
    newInstance[ConfigurableTranslator](transConf.getString("class"), Array(classOf[Config]), Array(transConf))
  }

  def report (channel: String, msg: Any): Unit = {

    val o = if (translator.isDefined){
      translator.get.translate(msg) match {
        case Some(x) => x
        case None => return // no translation, no output
      }
    } else msg

    ConsoleIO.synchronized {
      if (alert) {
        print(scala.Console.RED)
        SoundUtils.tone(500, 700, 0.6)
      }

      val msg = if (prefix.isDefined) prefix.get + o else o
      println(msg)
      ifSome(fos) { _.println(msg)}

      if (alert) print(scala.Console.RESET)
    }
  }

  def getPrefix: Option[String] = {
    prefix.map { _.replace("$date", raceActorSystem.simClock.dateTime.format_Hms)}
  }
}

/**
 * a probe translator that prefers toJson over toString
 */
class MaybeJsonTranslator (val config: Config) extends ConfigurableTranslator {
  val writer = new JsonWriter()
  writer.format(config.getBooleanOrElse("pretty", false))

  override def translate(src: Any): Option[Any] = {
    src match {
      case js: JsonSerializable => Some(writer.toNewJson(js))
      case other => Some(other)
    }
  }
}

/**
 * a probe translator that converts utf8 byte arrays to strings
 */
class Utf8DataToStringTranslator (val config: Config) extends ConfigurableTranslator {
  override def translate(src: Any): Option[Any] = {
    src match {
      case a: Array[Byte] => Some(new String(a, 0, a.length))
      case other => Some(other)
    }
  }
}