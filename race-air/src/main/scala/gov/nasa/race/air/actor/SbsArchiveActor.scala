/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import com.typesafe.config.Config
import gov.nasa.race.actor.{SocketArchiver, TextLineSocketArchiveWriter}

/**
  * a specialized SocketArchiver for SBS text lines received through a socket, assuimg the
  * default port usec by dump1090 (30003).
  *
  * Note that socket data might include incomplete lines, hence we have to use a TextLineSocketArchiveWriter.
  * Apart from incomplete lines we don't filter
  */
class SbsArchiveActor(val config: Config) extends SocketArchiver {
  override protected def defaultPort: Int = 30003
  override protected def createWriter = new TextLineSocketArchiveWriter
}
