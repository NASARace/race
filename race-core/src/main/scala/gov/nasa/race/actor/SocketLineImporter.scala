/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import com.typesafe.config.Config
import gov.nasa.race.common.{ByteSlice, SocketLineAcquisitionThread}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.PublishingRaceActor

import java.net.Socket

/**
  * a SocketImporter that publishes whatever gets computed from each received line of text
  *
  * default behavior is to
  */
class SocketLineImportActor (val config: Config) extends SocketImporter with PublishingRaceActor {

  val initLineLength = config.getIntOrElse("init-line", 256)
  val maxLineLength = config.getIntOrElse("max-line", 512)
  val publishRaw = config.getBooleanOrElse("publish-raw", false) // publish as Array[Byte]

  override protected def createDataAcquisitionThread(sock: Socket): Option[SocketDataAcquisitionThread] = {
    Some( new SocketLineAcquisitionThread(name, sock, initLineLength, maxLineLength, processLine))
  }

  // override for translation etc.
  protected def processLine (line: ByteSlice): Boolean = {
    if (line.nonEmpty) {
      if (publishRaw) publish( line.toByteArray) else publish( line.toString)
    }
    true // go on
  }
}
