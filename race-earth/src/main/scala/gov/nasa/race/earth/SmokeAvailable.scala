package gov.nasa.race.earth
import gov.nasa.race.common.FileAvailable // could not use file avail bc there are two files
import gov.nasa.race.uom.DateTime

import java.io.File

case class SmokeAvailable(satellite: String,              // satellite source, either goes-18 or goes-17
                          smokeFile: File,                // file that holds the smoke contours
                          cloudFile: File,                // file that holds the cloud contours
                          srs: String,                    // spec of spatial reference system
                          date: DateTime,                 // datetime of the satellite imagery used to generate the file
                         ) extends FileAvailable  {
  def file = smokeFile // need to include cloud
  def getData = date.format_yMd_Hms_z
  def toJsonWithUrl (url: String): String = {
    s"""{"smokeLayer":{"satellite":"$satellite","date":${date.toEpochMillis},"srs":"$srs","url":"$url"}}"""
  }
}
