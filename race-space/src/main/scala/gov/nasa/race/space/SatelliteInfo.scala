package gov.nasa.race.space

import com.typesafe.config.Config
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.geo.GeoPosition

/**
 * generic satellite info, normally initialized from config files
 */
class SatelliteInfo (
                      val satId: Int,     // unique NORAD catalog id
                      val name: String,
                      val description: String,
                      val show: Boolean = true
                    ) extends JsonSerializable {

  def this (conf: Config) = this(
    conf.getInt("sat-id"),
    conf.getString("name"),
    conf.getString("description"),
    conf.getBooleanOrElse("show", true)
  )

  override def serializeMembersTo(writer: JsonWriter): Unit = {
    writer
      .writeIntMember("satId", satId)
      .writeStringMember("name", name)
      .writeStringMember("description", description)
      .writeBooleanMember("show", show)
  }
}
