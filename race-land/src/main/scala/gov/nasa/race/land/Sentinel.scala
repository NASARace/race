package gov.nasa.race.land

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonSerializable, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Speed.MetersPerSecond
import gov.nasa.race.uom.{Angle, DateTime, Speed}
import gov.nasa.race.{Dated, ifSome}

import scala.collection.mutable.ArrayBuffer

object Sentinel {

  //--- lexical constants
  val DATA = asc("data")
  val TIME_RECORDED = asc("timeRecorded")
  val DEVICE_ID = asc("deviceId")
  val SENSOR_ID = asc("sensorId")

  val ID = asc("id")
  val DATE = asc("date")

  val GPS = asc("gps"); val LAT = asc("latitude"); val LON = asc("longitude")
  val MAG = asc("magnetometer"); val MX = asc("mx"); val MY = asc("my"); val MZ = asc("mz")
  val GYRO = asc("gyroscope"); val GX = asc("gx"); val GY = asc("gy"); val GZ = asc("gz")
  val ACCEL = asc("accelerometer"); val AX = asc("ax"); val AY = asc("ay"); val AZ = asc("az")
  val GAS = asc("gas"); val HUM = asc("hummidity"); val PRESS = asc("pressure"); val ALT = asc("altitude")
  val THERMO = asc("thermometer"); val TEMP = asc("temperature")
  val VOC = asc("voc"); val TVOC = asc("TVOC"); val ECO2 = asc("eCO2")
  val ANEMO = asc("anemometer"); val ANGLE = asc("angle"); val SPD = asc("speed")
  val FIRE = asc("fire"); val PROB = asc("fireProb")
  val CAMERA = asc("camera"); val IR = asc("ir"); val PATH = asc("filename")


  def writeReadingMemberTo (w: JsonWriter, name: CharSequence, date: DateTime)(f: JsonWriter=>Unit): Unit = {
    w.writeObjectMember(name) { w =>
      w.writeDateTimeMember(DATE,date)
      f(w)
    }
  }
}
import gov.nasa.race.land.Sentinel._

trait SentinelReading extends Dated with JsonSerializable

//--- the sentinel readings types

case class SentinelGpsReading (date: DateTime, lat: Angle, lon: Angle) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(LAT, lat.toDegrees)
    w.writeDoubleMember(LON, lon.toDegrees)
  }
}

/**
  * parses value 'null' or:
            {
                "latitude": 37.328366705311865,
                "longitude": -122.10084539864475,
                "recordId": 1065930
            },
  */
trait SentinelGpsParser extends UTF8JsonPullParser {
  def parseGpsValue(date: DateTime): Option[SentinelGpsReading] = {
    var lat = Angle.UndefinedAngle
    var lon = Angle.UndefinedAngle
    if (isInObject) {
      foreachMemberInCurrentObject {
        case LAT => lat = Degrees(unQuotedValue.toDouble)
        case LON => lon = Degrees(unQuotedValue.toDouble)
        case _ => // ignore other members
      }
      Some(SentinelGpsReading(date,lat,lon))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelGyroReading (date: DateTime, gx: Double, gy: Double, gz: Double) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(GX, gx)
    w.writeDoubleMember(GY, gy)
    w.writeDoubleMember(GZ, gz)
  }
}

/**
  * parses value 'null' or:
     {
      "gx": 9.886104239500455,
      "gy": -0.019948077582174595,
      "gz": 0.01958188436975299,
      ...
     }
  */
trait SentinelGyroParser extends UTF8JsonPullParser {
  def parseGyroValue(date: DateTime): Option[SentinelGyroReading] = {
    var gx: Double = 0
    var gy: Double = 0
    var gz: Double = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case GX => gx = unQuotedValue.toDouble
        case GY => gy = unQuotedValue.toDouble
        case GZ => gz = unQuotedValue.toDouble
        case _ => // ignore other members
      }
      Some(SentinelGyroReading(date,gx,gy,gz))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelMagReading (date: DateTime, mx: Double, my: Double, mz: Double) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(MX, mx)
    w.writeDoubleMember(MY, my)
    w.writeDoubleMember(MZ, mz)
  }
}

/**
  * parses value 'null' or:
     {
      "mx": 9.886104239500455,
      "my": -0.019948077582174595,
      "mz": 0.01958188436975299,
      ...
     }
  */
trait SentinelMagParser extends UTF8JsonPullParser {
  def parseMagValue(date: DateTime): Option[SentinelMagReading] = {
    var mx: Double = 0
    var my: Double = 0
    var mz: Double = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case MX => mx = unQuotedValue.toDouble
        case MY => my = unQuotedValue.toDouble
        case MZ => mz = unQuotedValue.toDouble
        case _ => // ignore other members
      }
      Some(SentinelMagReading(date,mx,my,mz))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelAccelReading (date: DateTime, ax: Double, ay: Double, az: Double) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(AX, ax)
    w.writeDoubleMember(AY, ay)
    w.writeDoubleMember(AZ, az)
  }
}
/**
  * parses value 'null' or:
     {
      "ax": 9.886104239500455,
      "ay": -0.019948077582174595,
      "az": 0.01958188436975299,
      ...
     }
  */
trait SentinelAccelParser extends UTF8JsonPullParser {
  def parseAccelValue(date: DateTime): Option[SentinelAccelReading] = {
    var ax: Double = 0
    var ay: Double = 0
    var az: Double = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case AX => ax = unQuotedValue.toDouble
        case AY => ay = unQuotedValue.toDouble
        case AZ => az = unQuotedValue.toDouble
        case _ => // ignore other members
      }
      Some(SentinelAccelReading(date,ax,ay,az))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelGasReading (date: DateTime, gas: Long, humidity: Double, pressure: Double, alt: Double) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeLongMember(GAS, gas)
    w.writeDoubleMember(HUM, humidity)
    w.writeDoubleMember(PRESS, pressure)
    w.writeDoubleMember(ALT, alt)
  }
}

/**
  * parses value 'null' or:
            "gas": {
                "gas": 100502,
                "humidity": 41.83471927704546,
                "pressure": 985.3858389187525,
                "altitude": 271.6421960490708,
                "recordId": 1065919
            },
  */
trait SentinelGasParser extends UTF8JsonPullParser {
  def parseGasValue(date: DateTime): Option[SentinelGasReading] = {
    var gas: Long = 0
    var humidity: Double = 0
    var pressure: Double = 0
    var altitude: Double = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case GAS => gas = unQuotedValue.toLong
        case HUM => humidity = unQuotedValue.toDouble
        case PRESS => pressure = unQuotedValue.toDouble
        case ALT => altitude = unQuotedValue.toDouble
        case _ => // ignore other members
      }
      Some(SentinelGasReading(date,gas,humidity,pressure,altitude))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelThermoReading (date: DateTime, temp: Double) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(TEMP, temp)
  }
}

/**
  * parses value 'null' or:
            {
                "temperature": 25.574097844252393,
                "recordId": 1065922
            },
  */
trait SentinelThermoParser extends UTF8JsonPullParser {
  def parseThermoValue(date: DateTime): Option[SentinelThermoReading] = {
    var temp: Double = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case TEMP => temp = unQuotedValue.toDouble
        case _ => // ignore other members
      }
      Some(SentinelThermoReading(date,temp))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelVocReading (date: DateTime, tvoc: Int, eco2: Int) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeIntMember(TVOC, tvoc)
    w.writeIntMember(ECO2, eco2)
  }
}

/**
  * parses value 'null' or:
            {
                "TVOC": 0,
                "eCO2": 400,
                "recordId": 1065927
            },
  */
trait SentinelVocParser extends UTF8JsonPullParser {
  def parseVocValue(date: DateTime): Option[SentinelVocReading] = {
    var tvoc: Int = 0
    var eco2: Int = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case TEMP => tvoc = unQuotedValue.toInt
        case ECO2 => eco2 = unQuotedValue.toInt
        case _ => // ignore other members
      }
      Some(SentinelVocReading(date,tvoc,eco2))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelAnemoReading(date: DateTime, dir: Angle, spd: Speed) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(ANGLE, dir.toDegrees)
    w.writeDoubleMember(SPD, spd.toMetersPerSecond)
  }
}

/**
  * parses value 'null' or:
             {
                "angle": 324.2628274722738,
                "speed": 0.04973375738796704,
                "recordId": 1065928
            },
  */
trait SentinelAnemoParser extends UTF8JsonPullParser {
  def parseWindValue(date: DateTime): Option[SentinelAnemoReading] = {
    var angle = Angle.UndefinedAngle
    var speed = Speed.UndefinedSpeed
    if (isInObject) {
      foreachMemberInCurrentObject {
        case ANGLE => angle = Degrees(unQuotedValue.toDouble)
        case SPD => speed = MetersPerSecond(unQuotedValue.toDouble)
        case _ => // ignore other members
      }
      Some(SentinelAnemoReading(date,angle,speed))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelFireReading (date: DateTime, prob: Double) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeDoubleMember(PROB, prob)
  }
}

/**
  * parses value 'null' or:
             {
                "fireProb": 0.9689663083869459,
                "recordId": 1065946
            },
  */
trait SentinelFireParser extends UTF8JsonPullParser {
  def parseFireValue(date: DateTime): Option[SentinelFireReading] = {
    var prob: Double = 0
    if (isInObject) {
      foreachMemberInCurrentObject {
        case PROB => prob = unQuotedValue.toDouble
        case _ => // ignore other members
      }
      Some(SentinelFireReading(date,prob))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

case class SentinelCameraReading (date: DateTime, sensorId: Int, isIR: Boolean, path: String) extends SentinelReading {
  def serializeMembersTo(w: JsonWriter): Unit = {
    w.writeDateTimeMember(DATE, date)
    w.writeIntMember(ID,sensorId)
    w.writeBooleanMember(IR,isIR)
    w.writeStringMember(PATH,path)
  }
}

/**
  * parses value 'null' or:
           {
                "filename": "./camera/4c292b0c-a270-4015-a9f6-006fc8641f73.webp",
                "isInfrared": true,
                "recordId": 1065944
            }
  */
trait SentinelCameraParser extends UTF8JsonPullParser {
  def parseCameraValue(date: DateTime,sensorId: Int): Option[SentinelCameraReading] = {
    var isIR: Boolean = false
    var filename: String = null
    if (isInObject) {
      foreachMemberInCurrentObject {
        case IR => isIR = unQuotedValue.toBoolean
        case PATH => filename = quotedValue.asString
        case _ => // ignore other members
      }
      Some(SentinelCameraReading(date,sensorId,isIR,filename))
    } else if (isNull) None
    else throw exception("expected object value")
  }
}

/**
  * class representing a Sentinel device state
  */
case class Sentinel (
                      id: Int, // deviceId
                      date: DateTime = DateTime.UndefinedDateTime, // last sensor update
                      gps: Option[SentinelGpsReading] = None,
                      gyro: Option[SentinelGyroReading] = None,
                      mag: Option[SentinelMagReading] = None,
                      accel: Option[SentinelAccelReading] = None,
                      gas: Option[SentinelGasReading] = None,
                      thermo: Option[SentinelThermoReading] = None,
                      voc: Option[SentinelVocReading] = None,
                      anemo: Option[SentinelAnemoReading] = None,
                      fire: Option[SentinelFireReading] = None,
                      images: Seq[SentinelCameraReading] = Seq.empty
               ) extends Dated with JsonSerializable {

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeIntMember(ID, id)
    w.writeDateTimeMember(DATE,date)
    ifSome(gps){ w.writeObjectMember(GPS,_) }
    ifSome(gyro) { w.writeObjectMember(GYRO,_) }
    ifSome(mag) { w.writeObjectMember(MAG,_) }
    ifSome(accel) { w.writeObjectMember(ACCEL,_) }
    ifSome(gas) { w.writeObjectMember(GAS,_) }
    ifSome(thermo) { w.writeObjectMember(THERMO,_) }
    ifSome(voc) { w.writeObjectMember(VOC,_) }
    ifSome(anemo) { w.writeObjectMember(ANEMO,_) }
    ifSome(fire){ w.writeObjectMember(FIRE,_) }
    if (images.nonEmpty) {
      w.writeArrayMember(CAMERA) { w=>
        images.foreach( img=> w.writeObject( img.serializeMembersTo))
      }
    }
  }

  def updateWith (update: SensorUpdate): Sentinel = {
    if (update.deviceId != id) return this  // not for us

    update.reading match {
      case r: SentinelGpsReading => copy(date=r.date, gps=Some(r))
      case r: SentinelGyroReading => copy(date=r.date, gyro=Some(r))
      case r: SentinelMagReading => copy(date=r.date, mag=Some(r))
      case r: SentinelAccelReading => copy(date=r.date, accel=Some(r))
      case r: SentinelGasReading => copy(date=r.date, gas=Some(r))
      case r: SentinelThermoReading => copy(date=r.date, thermo=Some(r))
      case r: SentinelVocReading => copy(date=r.date, voc=Some(r))
      case r: SentinelAnemoReading => copy(date=r.date, anemo=Some(r))
      case r: SentinelFireReading => copy(date=r.date, fire=Some(r))
      case r: SentinelCameraReading => copy(date=r.date, images=addImage(r))
    }
  }

  def addImage (r: SentinelCameraReading): Seq[SentinelCameraReading] = {
    images // TODO - replace based on sensorId and isIR
  }
}

case class SensorUpdate(deviceId: Int, reading: SentinelReading)

class SentinelParser extends UTF8JsonPullParser
    with SentinelAccelParser with SentinelAnemoParser with SentinelGasParser with SentinelMagParser with SentinelThermoParser
    with SentinelFireParser with SentinelGyroParser with SentinelCameraParser with SentinelVocParser with SentinelGpsParser {

  def parse(): Seq[SensorUpdate] = {
    val updates = ArrayBuffer.empty[SensorUpdate]
    var deviceId: Int = -1
    var sensorId = -1
    var timeRecorded = DateTime.UndefinedDateTime

    def appendSomeRecording (maybeReading: Option[SentinelReading]): Unit = {
      ifSome(maybeReading){ r=>
        if (deviceId != -1 && timeRecorded.isDefined) {
          updates += SensorUpdate(deviceId, r)
        }
      }
    }

    ensureNextIsObjectStart()
    foreachMemberInCurrentObject {
      case DATA =>
        foreachElementInCurrentArray {
          deviceId = -1
          sensorId = -1
          timeRecorded = DateTime.UndefinedDateTime

          foreachMemberInCurrentObject {
            case DEVICE_ID => deviceId = unQuotedValue.toInt
            case SENSOR_ID => sensorId = unQuotedValue.toInt
            case TIME_RECORDED => timeRecorded = unQuotedValue.toDateTime
            case GPS => appendSomeRecording( parseGpsValue(timeRecorded))
            case GAS => appendSomeRecording( parseGasValue(timeRecorded))
            case ACCEL => appendSomeRecording( parseAccelValue(timeRecorded))
            case ANEMO => appendSomeRecording( parseWindValue(timeRecorded))
            case GYRO => appendSomeRecording( parseGyroValue(timeRecorded))
            case THERMO => appendSomeRecording( parseThermoValue(timeRecorded))
            case MAG => appendSomeRecording( parseMagValue(timeRecorded))
            case FIRE => appendSomeRecording( parseFireValue(timeRecorded))
            case VOC => appendSomeRecording( parseVocValue(timeRecorded))
            case CAMERA => appendSomeRecording( parseCameraValue(timeRecorded,sensorId))
            case _ => // ignore other members
          }
        }

      case _ => // ignore other members
    }

    updates.toSeq
  }
}
