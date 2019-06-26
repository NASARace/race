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

package gov.nasa.race

import com.typesafe.config.Config
import scala.collection.Seq

/**
  * common functions and types for remote races
  */
package object launcher {

  final val DefaultRemotePort: Int = 8767 // the default port for communication between RemoteLauncher and RemoteMain

  /**
    * the data we need to send to RemoteMain in order to launch RaceActorSystems
    * Note - this is transmitted between processes, so it needs to be serializable
    *
    * LaunchData is only supposed to be transmitted on secure channels (or within the same
    * protection domain), so we don't have to encrypt the configs. However, the vault
    * data is never stored in clear, so we shouldn't expose it here
    */
  case class LaunchConfigSpec(configs: Seq[Config], vaultData: Option[Array[Byte]])
}
