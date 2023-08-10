package gov.nasa.race.earth
import gov.nasa.race.common.FileAvailable
import gov.nasa.race.uom.DateTime

import java.io.File

case class SmokeAvailable(satellite: String,              // satellite source, either goes-18 or goes-17
                          file: File,                // file that holds the smoke or cloud contours
                          scType: String,
                          srs: String,                    // spec of spatial reference system
                          date: DateTime,                 // datetime of the satellite imagery used to generate the file
                         ) extends FileAvailable  {

  def toJsonWithUrl (url: String): String = {
    s"""{"smokeLayer":{"satellite":"$satellite","date":${date.toEpochMillis},"srs":"$srs", "scType": "$scType", "url":"$url"}}"""
  }
}
