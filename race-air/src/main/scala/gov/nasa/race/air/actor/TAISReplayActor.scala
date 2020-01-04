/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import java.io.InputStream

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.actor.Replayer
import gov.nasa.race.air.translator.TATrackAndFlightPlanParser
import gov.nasa.race.archive.TaggedArchiveReader
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}

/**
  * a TaggedArchiveReader that can parse TAIS tagged archives
  */
class TAISReader (val iStream: InputStream, val pathName: String="<unknown>", val initBufferSize: Int)
  extends TATrackAndFlightPlanParser with TaggedArchiveReader {

  def this(conf: Config) = this(createInputStream(conf), // this takes care of optional compression
    configuredPathName(conf),
    conf.getIntOrElse("buffer-size", 4096))

  override protected def parseEntryData(limit: Int): Any = {
    parse(buf, 0, limit)
  }
}


/**
  * specialized Replayer for TAIS tagged archives
  */
class TAISReplayActor (val config: Config) extends Replayer[TAISReader] {
  override def createReader = new TAISReader(config)
}
