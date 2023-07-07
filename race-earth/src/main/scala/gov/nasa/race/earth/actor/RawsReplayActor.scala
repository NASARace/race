/*
 * Copyright (c) 2023, United States Government, as represented by the
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
package gov.nasa.race.earth.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.FileReplayActor
import gov.nasa.race.earth.{RawsParser, WxStation, WxStationAvailable}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import scala.collection.mutable.Map
import java.io.File
import java.nio.file.{Path, PathMatcher}

object RawsPathMatcher extends PathMatcher {
  val rawsRE = """wx_station-(.+)-(\d{4})(\d{2})(\d{2})-t(\d{2})(\d{2})z.csv""".r

  def matches (path: Path): Boolean = rawsRE.matches(path.getFileName.toString)
}

/**
 * replay actor for a directory that contains RAWS files of the form:
 *
 *   wx_station-LSGC1-20230411-t0618z.csv
 */
class RawsReplayActor (val config: Config) extends FileReplayActor[WxStationAvailable] {

  val wxStations: Map[String,WxStation] = Map.empty
  val parser = new RawsParser

  override protected def getPathMatcher(): PathMatcher = RawsPathMatcher

  override protected def getFileAvailable (file: File) : Option[WxStationAvailable] = {
    file.getName match {
      case RawsPathMatcher.rawsRE(id, yr, mon, day, h, m) =>
        try {
          val wx = wxStations.getOrElseUpdate( id, parser.getWxStationFrom( FileUtils.fileContentsAsBytes(file).get).get)
          val date = DateTime(yr.toInt, mon.toInt, day.toInt, h.toInt, m.toInt, 0,0,DateTime.utcId)
          Some( WxStationAvailable(wx,file,date))
        } catch {
          case x: Throwable =>
            warning(s"malformed RAWS file: $file (${x.getMessage})")
            None
        }
      case _ => None
    }
  }

  override protected def publishFileAvailable(wxa: WxStationAvailable): Unit = publish(wxa)
}
