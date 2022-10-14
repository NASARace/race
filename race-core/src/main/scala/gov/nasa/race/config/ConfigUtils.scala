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

import java.awt.{Color, Font}
import java.io.File
import com.typesafe.config._
import gov.nasa.race.core.RaceException
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed, Time}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Time.{Milliseconds, UndefinedTime}
import gov.nasa.race.util._

import java.util
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._

/**
 * Config is just an interface, i.e. we can't use it as a Map without implicit
 * conversions
 */
object ConfigUtils {

  val keyValRE = " *(.+): *(.*)".r

  def showConfig( config: Config) = {
    config.root.render( ConfigRenderOptions.defaults().setComments(false).setOriginComments(false))
  }

  def getWithFallback[T] (key: String, fallback: T)(f: =>T) = {
    try {
      f
    } catch {
      case _: ConfigException.Missing => fallback
    }
  }

  def getOptional[T] (key: String)(f: => T): Option[T] = {
    try {
      Some(f)
    } catch {
      case _: ConfigException.Missing => None
    }
  }

  def render(conf: Config): String = {
    val renderOpts = ConfigRenderOptions.defaults().setOriginComments(false).setJson(false)
    conf.root.render(renderOpts)
  }

  def createConfig (es: (String,Any)*): Config = {
    es.foldLeft(ConfigFactory.empty)( (conf,e) => conf.withValue(e._1, ConfigValueFactory.fromAnyRef(e._2)))
  }

  def createConfig (s: String): Config = ConfigFactory.parseString(s)

  /**
    * implicit class to give the (Java) Config a more Scala Map like API
    */
  implicit class ConfigWrapper(val conf: Config) {
    final val CRYPT_MARKER = "??"

    def hasPaths (key: String*): Boolean = {
      for (k <- key) if (!conf.hasPath(k)) return false
      true
    }

    def getMappedStringOrElse[T] (key: String, f: String=>T, default: =>T): T = {
      try {
        f( conf.getString(key))
      }catch {
        case _: ConfigException.Missing => default
      }
    }

    //--- these are just convenience forwarders so that we don't have to import ConfigVault everywhere
    def getVaultString (key: String): String = ConfigVault.getString(key)
    def getVaultStringOrElse (key: String, defaultValue: String): String = ConfigVault.getStringOrElse(key,defaultValue)
    def getOptionalVaultString (key: String): Option[String] = ConfigVault.getOptionalString(key)

    def getVaultInt (key: String): Int = ConfigVault.getInt(key)
    def getVaultLong (key: String): Long = ConfigVault.getLong(key)
    def getVaultDouble (key: String): Double = ConfigVault.getDouble(key)

    // getVaultableX are used to access config options that can have values referring to
    // vault entries (the values are strings consisting of a '??' marker followed by the
    // respective vault key name)

    def getVaultableString(key: String): String = {
      val s = conf.getString(key)
      if (s.startsWith(CRYPT_MARKER)) {
        ConfigVault.getString(s.substring(CRYPT_MARKER.length))
      } else s
    }

    def getVaultableStringOrElse(key: String, defaultValue: String): String = {
      try {
        getVaultableString(key)
      } catch {
        case _: ConfigException.Missing => defaultValue
      }
    }

    def getVaultableInt(key: String): Int = {
      var s = conf.getString(key)
      if (s.startsWith(CRYPT_MARKER)) {
        s = ConfigVault.getString(s.substring(CRYPT_MARKER.length))
      }
      s.toInt
    }

    def getVaultableIntOrElse(key: String, defaultValue: Int): Int = {
      try {
        getVaultableInt(key)
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

    def getOptionalVaultableChars (key: String): Option[Array[Char]] = {
      getOptionalVaultableString(key).map( _.toCharArray)
    }

    def getStringOrElse(key: String, fallback: String) = getWithFallback(key,fallback)(conf.getString(key))
    def getOptionalString(key: String): Option[String] = getOptional(key)(conf.getString(key))

    def getOverridableStringOrElse (key: String, fallback: String): String = {
      val sysProp = System.getProperty(key.replace('-', '.'))
      if (sysProp != null) {
        sysProp
      } else {
        getStringOrElse( key, fallback)
      }
    }

    def getFloatOrElse(key: String, fallback: Float) = getWithFallback(key,fallback)( conf.getDouble(key).toFloat )
    def getOptionalFloat(key: String): Option[Float] = getOptional(key)( conf.getDouble(key).toFloat )

    def getDoubleOrElse(key: String, fallback: Double) = getWithFallback(key,fallback)( conf.getDouble(key) )
    def getOptionalDouble(key: String): Option[Double] = getOptional(key)( conf.getDouble(key) )

    def getDoubleSeq (key: String): Seq[Double] = {
      try {
        Seq.from(conf.getDoubleList(key).asScala).map(_.doubleValue)
      } catch {
        case _: ConfigException.Missing => Seq.empty[Double]
      }
    }
    def getDoubleArray (key: String): Array[Double] = getDoubleSeq(key).toArray[Double]

    def getGeoPositionSeq (key: String): Seq[GeoPosition] = {
      try {
        Seq.from(conf.getList(key).asScala).map { cv =>
          val ds = cv.unwrapped().asInstanceOf[util.ArrayList[Number]]
          val lat = ds.get(0).doubleValue()
          val lon = ds.get(1).doubleValue()
          val alt = if (ds.size() > 2) ds.get(2).doubleValue() else 0.0
          GeoPosition.fromDegreesAndMeters(lat,lon,alt)
        }
      } catch {
        case _: ConfigException.Missing => Seq.empty[GeoPosition]
      }
    }
    def getGeoPositionArray (key: String): Array[GeoPosition] = getGeoPositionSeq(key).toArray

    def getDateTimeOrElse(key: String, fallback: DateTime) = getWithFallback(key,fallback)( DateTime.parseWithOptionalTime(conf.getString(key)) )
    def getOptionalDateTime(key: String): DateTime = {
      try {
        val s = conf.getString(key)
        DateTime.parseWithOptionalTime(s)
      } catch {
        case _: ConfigException.Missing => DateTime.UndefinedDateTime
      }
    }

    def getDurationTimeOrElse (key: String, fallback: Time): Time = {
      getOptionalFiniteDuration(key) match {
        case Some(dur) => Milliseconds(dur.toMillis)
        case None => fallback
      }
    }
    def getOptionalDurationTime (key: String): Time = getDurationTimeOrElse(key, UndefinedTime)


    def getBooleanOrElse(key: String, fallback: Boolean) = getWithFallback(key,fallback)(conf.getBoolean(key))
    def getOptionalBoolean(key: String): Option[Boolean] = getOptional(key)( conf.getBoolean(key) )

    def getOverridableBooleanOrElse (key: String, fallback: Boolean): Boolean = {
      val sysProp = System.getProperty(key.replace('-', '.'))
      if (sysProp != null) {
        sysProp.equalsIgnoreCase("true")
      } else {
        getBooleanOrElse( key, fallback)
      }
    }

    def getIntOrElse(key: String, fallback: Int) = getWithFallback(key,fallback)(conf.getInt(key))
    def getOptionalInt(key: String): Option[Int] = getOptional(key)( conf.getInt(key) )

    def getIntSeq (key: String): Seq[Int] = {
      try {
        Seq.from(conf.getIntList(key).asScala).map(_.intValue)
      } catch {
        case _: ConfigException.Missing => Seq.empty[Int]
      }
    }
    def getIntArray (key: String): Array[Int] = getIntSeq(key).toArray[Int]

    def getOverridableIntOrElse (key: String, fallback: Int): Int = {
      val sysProp = System.getProperty(key.replace('-', '.'))
      if (sysProp != null) {
        Integer.parseInt(sysProp)
      } else {
        getIntOrElse( key, fallback)
      }
    }

    def getLongOrElse(key: String, fallback: Long) = getWithFallback(key,fallback)(conf.getLong(key))
    def getOptionalLong(key: String): Option[Long] = getOptional(key)( conf.getLong(key) )

    def getLongSeq (key: String): Seq[Long] = {
      try {
        Seq.from(conf.getLongList(key).asScala).map(_.longValue)
      } catch {
        case _: ConfigException.Missing => Seq.empty[Long]
      }
    }
    def getLongArray (key: String): Array[Long] = getLongSeq(key).toArray[Long]

    def getStringSeq (key: String): Seq[String] = {
      try {
        Seq.from(conf.getStringList(key).asScala)
      } catch {
        case _: ConfigException.Missing => Seq.empty[String]
      }
    }
    def getStringArray (key: String): Array[String] = {
      try {
        toArray(conf.getStringList(key))
      } catch {
        case _: ConfigException.Missing => Array.empty[String]
      }
    }

    def getStrings (key: String): Array[String] = {
      try {
        toArray(conf.getStringList(key))
      } catch {
        case _: ConfigException.WrongType =>
          try {
            Array(conf.getString(key))
          } catch {
            case _: ConfigException.Missing => Array.empty[String]
          }
        case _: ConfigException.Missing => Array.empty[String]
      }
    }

    def getNonEmptyStringsOrElse (key: String, fallback: => Array[String]): Array[String] = {
      val ss = getStrings(key)
      if (ss.nonEmpty) ss else fallback
    }

    def getStringListOrElse(key: String, fallbackValue: Seq[String]): Seq[String] = {
      try {
        conf.getStringList(key).asScala.toSeq
      } catch {
        case _: ConfigException.Missing => fallbackValue
      }
    }
    def getOptionalStringList(key: String): Seq[String] = {
      try {
        conf.getStringList(key).asScala.toSeq
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

    def getConfigOrElse(key: String, fallback: Config): Config = getWithFallback(key,fallback)(conf.getConfig(key))
    def getOptionalConfig(key: String): Option[Config] = getOptional(key)( conf.getConfig(key) )

    def getOptionalConfigList(key: String): Seq[Config] = {
      try {
        conf.getConfigList(key).asScala.toSeq
      } catch {
        case _: ConfigException.Missing => Nil
      }
    }

    def getConfigSeq(key: String): Seq[Config] = {
      try {
        conf.getConfigList(key).asScala.toSeq
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



    def getKeyValuePairsOrElse(key: String, fallback: =>Seq[(String,String)]): Seq[(String,String)] = {
      try {
        conf.getStringList(key).asScala.toSeq.map { s=>
          keyValRE.findFirstIn(s) match {
            case Some(keyValRE(k,v)) => (k,v)
            case _ => throw new ConfigException.Generic(s"illegal key/value spec: '$s'")
          }
        }
      } catch {
        case _: ConfigException.Missing => fallback
      }
    }

    //--- value type extensions

    def getColor(key: String): Color = {
      conf.getString(key).toLowerCase() match {
        case "red" => Color.red
        case "blue" => Color.blue
        case "green" => Color.green
        case "yellow" => Color.yellow
        case "cyan" => Color.cyan
        case "magenta" => Color.magenta
        case "pink" => Color.pink
        case "orange" => Color.orange
        case "lightgray" => Color.lightGray
        case "darkgray" => Color.darkGray
        case "gray" => Color.gray
        case "white" => Color.white
        case "black" => Color.black
        case s => decodeColor(s) // unfortunately Color.decode does not support transparency
      }
    }

    val rgbaRE = """^(?:#|0x)?([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])([0-9A-Fa-f][0-9A-Fa-f])?$""".r

    // we need our own to support transparency
    def decodeColor (s: String): Color = {
      // all orders assumed [a,]r,g,b
      if (s.contains(',')) { // list of ints
        val vs = s.split("\\s*,\\s*").map(Integer.parseInt)
        if (vs.length == 4) new Color(vs(1),vs(2),vs(3),vs(0))
        else if (vs.length == 3) new Color(vs(0),vs(1),vs(2))
        else throw new RuntimeException(s"unknown color format $s")
      } else {
        s match {
          case rgbaRE(r,g,b,a) =>
            val alpha = if (a != null) Integer.parseInt(a,16) else 255
            new Color(Integer.parseInt(r,16),Integer.parseInt(g,16),Integer.parseInt(b,16),alpha)
          case _ => throw new ConfigException.Generic(s"illegal color format $s")
        }
      }
    }

    def getColorOrElse(key: String, fallback: Color): Color = getWithFallback(key,fallback)(getColor(key))
    def getOptionalColor(key: String): Option[Color] = getOptional(key)( getColor(key) )

    def getFont (key: String): Font = Font.decode(conf.getString(key))

    def getFontOrElse (key: String, fallback: Font): Font = getWithFallback(key,fallback)(getFont(key))
    def getOptionalFont(key: String): Option[Font] = getOptional(key)( getFont(key) )

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

    def getGeoPosition(key: String): GeoPosition = {
      val pos = conf.getConfig(key)
      try {
        val lat = pos.getDouble("lat")
        val lon = pos.getDouble("lon")

        GeoPosition.fromDegrees(lat,lon)
      } catch {
        case _ : Throwable => throw new ConfigException.Generic("illegal LatLonPos format (expect {lat=<double>,lon=<double>})")
      }
    }
    def getOptionalGeoPosition(key: String): Option[GeoPosition] = getOptional(key)(getGeoPosition(key))

    def getFiniteDuration (key: String): FiniteDuration = conf.getDuration(key).toMillis.milliseconds
    def getFiniteDurationOrElse (key: String, fallback: FiniteDuration): FiniteDuration = getWithFallback(key,fallback)(getFiniteDuration(key))
    def getOptionalFiniteDuration (key: String): Option[FiniteDuration] = getOptional(key)( getFiniteDuration(key) )

    def getFiniteDurationSeq (key: String): Seq[FiniteDuration] = {
      try {
        conf.getDurationList(key).asScala.map( _.toMillis.milliseconds).toSeq
      } catch {
        case _: ConfigException.Missing => Seq.empty[FiniteDuration]
      }
    }

    def getTimeSeq (key: String): Seq[Time] = {
      try {
        conf.getDurationList(key).asScala.map( d=> Milliseconds(d.toMillis)).toSeq
      } catch {
        case _: ConfigException.Missing => Seq.empty[Time]
      }
    }

    def getOverridableDurationOrElse (key: String, fallback: FiniteDuration): FiniteDuration = {
      val sysProp = System.getProperty(key.replace('-', '.'))
      if (sysProp != null) {
        Duration.create(sysProp).asInstanceOf[FiniteDuration]
      } else {
        getFiniteDurationOrElse( key, fallback)
      }
    }

    def getExistingDir (key: String): File = {
      val dir = new File(conf.getString(key))
      if (dir.isDirectory) dir else throw new ConfigException.Generic(s"directory not found: $dir")
    }

    def getExistingDirOrElse (key: String, fallbackAction: =>File): File = {
      try {
        val dir = new File(conf.getString(key))
        if (dir.isDirectory) dir else fallbackAction
      } catch {
        case _: ConfigException.Missing => fallbackAction
      }
    }

    def getNonEmptyFile (key: String): File = {
      val file = new File(conf.getString(key))
      if (file.isFile && file.length() > 0) {
        file
      } else throw new ConfigException.Generic(s"file not found: $file")
    }

    def getOptionalFile (key: String): Option[File] = {
      try {
        Some(new File(conf.getString(key)))
      } catch {
        case _: ConfigException.Missing => None
      }
    }

    def translateFile[T] (key: String)(f: Array[Byte]=>Option[T]): Option[T] = {
      val file = new File(conf.getString(key))

      if (file.isFile && file.length() > 0) {
        f( FileUtils.fileContentsAsBytes(file).get)
      } else None
    }

    final val unitNumberRE = """^([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)([a-zA-Z/]+)?$""".r

    def getLength (key: String): Length = {
      val v = conf.getValue(key)
      v.unwrapped match {
        case s: String =>
          s match {
            case unitNumberRE(n,u) =>
              val d = n.toDouble
              u match {
                case null => Meters(d)
                case "m" => Meters(d)
                case "ft" => Feet(d)
                case "km" => Kilometers(d)
                case "nm" => NauticalMiles(d)
                  //... and more
                case other => throw new ConfigException.Generic(s"unknown length unit: $other")
              }
            case _ =>  throw new ConfigException.Generic(s"invalid length format: $s")
          }
        case n: Number => Meters(n.doubleValue)
        case other => throw new ConfigException.Generic(s"wrong length type: $other")
      }
    }

    def getLengthOrElse (key: String, fallbackAction: =>Length): Length = getWithFallback(key,fallbackAction)(getLength(key))


    def getSpeed (key: String): Speed = {
      val v = conf.getValue(key)
      v.unwrapped match {
        case s: String =>
          s match {
            case unitNumberRE(n,u) =>
              val d = java.lang.Double.parseDouble(n)
              u match {
                case null => MetersPerSecond(d)
                case "m/s" => MetersPerSecond(d)
                case "kn" => Knots(d)
                case "mph" => UsMilesPerHour(d)
                case "kmh" => KilometersPerHour(d)
                case other => throw new ConfigException.Generic(s"unknown speed unit: $other")
              }
            case _ =>  throw new ConfigException.Generic(s"invalid speed format: $s")
          }
        case n: Number => MetersPerSecond(n.doubleValue)
        case other => throw new ConfigException.Generic(s"wrong speed type: $other")
      }
    }

    def getSpeedOrElse (key: String, fallbackAction: =>Speed): Speed = getWithFallback(key,fallbackAction)(getSpeed(key))

    def getAngleOrElse (key: String, fallbackAction: =>Angle): Angle = getWithFallback(key,fallbackAction)(Degrees(conf.getDouble(key)))

    //--- scope operations

    def withConfigForPath(key: String)(f: Config=> Any) = if (conf.hasPath(key)) f(conf.getConfig(key))

    def withStringValue (k: String, s: String) = conf.withValue(k, ConfigValueFactory.fromAnyRef(s))
    def withIntValue (k: String, n: Int) = conf.withValue(k, ConfigValueFactory.fromAnyRef(n))
    def withDoubleValue (k: String, d:Double) = conf.withValue(k, ConfigValueFactory.fromAnyRef(d))
    def withBooleanValue (k: String, b:Boolean) = conf.withValue(k, ConfigValueFactory.fromAnyRef(b))

    //--- misc

    def addIfAbsent (key: String, f: => Any): Config = {
      if (!conf.hasPath(key)) conf.withValue(key, ConfigValueFactory.fromAnyRef(f)) else conf
    }
  }
}
