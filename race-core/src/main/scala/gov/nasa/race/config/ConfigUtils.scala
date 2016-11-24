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

import java.awt.Color
import java.io.File

import com.github.nscala_time.time.Imports._
import com.typesafe.config._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import gov.nasa.race.util._

/**
 * Config is just an interface, i.e. we can't use it as a Map without implicit
 * conversions
 */
object ConfigUtils {

  def emptyConfig = ConfigFactory.empty

  def showConfig( config: Config) = {
    config.root.render( ConfigRenderOptions.defaults().setComments(false).setOriginComments(false))
  }

  def render(conf: Config): String = {
    val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).setJson(false)
    conf.root.render(renderOpts)
  }


  /**
    * implicit class to give the (Java) Config a more Scala Map like API
    */
  implicit class ConfigWrapper(val conf: Config) {
    final val CRYPT_MARKER = "??"

    def hasPaths (key: String*): Boolean = {
      for (k <- key) if (!conf.hasPath(k)) return false
      true
    }

    def getVaultableString(key: String): String = {
      val s = conf.getString(key)
      if (s.startsWith(CRYPT_MARKER)) {
        ConfigVault.getString(s.substring(CRYPT_MARKER.length))
      } else s
    }

    def getVaultableStringOrElse(key: String, defaultValue: String): String = {
      try {
        val s = conf.getString(key)
        if (s.startsWith(CRYPT_MARKER)) {
          ConfigVault.getStringOrElse(s.substring(CRYPT_MARKER.length),defaultValue)
        } else s
      } catch {
        case _: ConfigException.Missing => defaultValue
      }
    }

    def getOptionalVaultableString(key: String): Option[String] = {
      try {
        val s = conf.getString(key)
        if (s.startsWith(CRYPT_MARKER)) {
          ConfigVault.getOptionalString(s.substring(CRYPT_MARKER.length))
        } else Some(s)
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getStringOrElse(key: String, defaultValue: String) = {
      try {
        conf.getString(key)
      } catch {
        case _: ConfigException.Missing => defaultValue
      }
    }
    def getOptionalString(key: String): Option[String] = {
      try {
        Some(conf.getString(key))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getDoubleOrElse(key: String, fallbackValue: Double) = {
      try {
        conf.getDouble(key)
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalDouble(key: String): Option[Double] = {
      try {
        Some(conf.getDouble(key))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getDateTimeOrElse(key: String, fallbackValue: DateTime) = {
      try {
        DateTime.parse(conf.getString(key))
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalDateTime(key: String): Option[DateTime] = {
      try {
        Some(DateTime.parse(conf.getString(key)))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getBooleanOrElse(key: String, fallbackValue: Boolean) = {
      try {
        conf.getBoolean(key)
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalBoolean(key: String): Option[Boolean] = {
      try {
        Some(conf.getBoolean(key))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getIntOrElse(key: String, fallbackValue: Int) = {
      try {
        conf.getInt(key)
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalInt(key: String): Option[Int] = {
      try {
        Some(conf.getInt(key))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getStringArray (key: String): Array[String] = {
      try {
        toArray(conf.getStringList(key))
      } catch {
        case _: ConfigException.Missing => Array.empty[String]
      }
    }
    def getStringListOrElse(key: String, fallbackValue: Seq[String]): Seq[String] = {
      try {
        conf.getStringList(key).asScala
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalStringList(key: String): Seq[String] = {
      try {
        conf.getStringList(key).asScala
      } catch {
        case _: ConfigException.WrongType =>
          try {
            Seq(conf.getString(key))
          } catch {
            case _: ConfigException.Missing => Nil
          }
        case _: ConfigException.Missing => Nil
      }
    }

    def getConfigOrElse(key: String, fallBack: Config): Config = {
      try {
        conf.getConfig(key)
      } catch {
        case _: ConfigException.Missing => fallBack
      }
    }
    def getOptionalConfig(key: String): Option[Config] = {
      try {
        Some(conf.getConfig(key))
      } catch {
        case _: ConfigException.Missing => None
      }
    }
    def getOptionalConfigList(key: String): Seq[Config] = {
      try {
        conf.getConfigList(key).asScala
      } catch {
        case _: ConfigException.Missing => Nil
      }
    }

    def getConfigSeq(key: String): Seq[Config] = {
      try {
        conf.getConfigList(key).asScala
      } catch {
        case _: ConfigException.Missing => Seq.empty
      }
    }
    def getConfigArray (key: String): Array[Config] = {
      try {
        toArray(conf.getConfigList(key))
      } catch {
        case _: ConfigException.Missing => Array.empty
      }
    }

    def getMapOrElse(key: String, fallBack: Map[String,String]): Map[String,String] = {
      try {
        conf.getConfig(key).entrySet.asScala.foldLeft(Map.empty[String,String]) { (acc,e) =>
          acc + (e.getKey -> e.getValue.toString)
        }
      } catch {
        case _: ConfigException.Missing => fallBack
      }
    }

    def getColorOrElse(key: String, fallBack: Color): Color = {
      try {
        conf.getString(key).toLowerCase() match {
          case "red" => Color.red
          case "blue" => Color.blue
          case "green" => Color.green
          case "yellow" => Color.yellow
          case "cyan" => Color.cyan
          case "magenta" => Color.magenta
          case "white" => Color.white
          case "black" => Color.black
          case s => Color.decode(s)
        }
      } catch {
        case t: Throwable => fallBack
      }
    }

    def getClassName (key: String): String = {
      val clsName = conf.getString("class")
      if (clsName.startsWith(".")) {
        "gov.nasa.race" + clsName
      } else {
        clsName
      }
    }

    def getDimensionOrElse (key: String, fallbackValue: (Int,Int)): (Int,Int) = {
      try {
        val jlist = conf.getIntList(key)
        (jlist.get(0), jlist.get(1))
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }

    def getFiniteDuration (key: String): FiniteDuration = conf.getDuration(key).toMillis.milliseconds
    def getFiniteDurationOrElse (key: String, fallbackValue: FiniteDuration): FiniteDuration = {
      try {
        val jDuration = conf.getDuration(key)
        jDuration.toMillis.milliseconds
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalFiniteDuration (key: String): Option[FiniteDuration] = {
      try {
        val jDuration = conf.getDuration(key)
        Some(jDuration.toMillis.milliseconds)
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def getExistingDirOrElse (key: String, fallbackAction: =>File): File = {
      try {
        val dir = new File(conf.getString(key))
        if (dir.isDirectory) dir else fallbackAction
      } catch {
        case _: ConfigException.Missing => fallbackAction
      }
    }

    def getOptionalFile (key: String): Option[File] = {
      try {
        Some(new File(conf.getString(key)))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def withConfigForPath(key: String)(f: Config=> Any) = if (conf.hasPath(key)) f(conf.getConfig(key))

    def addIfAbsent (key: String, f: => Any): Config = {
      if (!conf.hasPath(key)) conf.withValue(key, ConfigValueFactory.fromAnyRef(f)) else conf
    }

    def withStringValue (k: String, s: String) = conf.withValue(k, ConfigValueFactory.fromAnyRef(s))
    def withIntValue (k: String, n: Int) = conf.withValue(k, ConfigValueFactory.fromAnyRef(n))
    def withDoubleValue (k: String, d:Double) = conf.withValue(k, ConfigValueFactory.fromAnyRef(d))
    def withBooleanValue (k: String, b:Boolean) = conf.withValue(k, ConfigValueFactory.fromAnyRef(b))
  }
}
