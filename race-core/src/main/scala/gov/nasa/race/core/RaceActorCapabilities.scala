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
package gov.nasa.race.core

object RaceActorCapabilities {
  //--- general capabilities
  final val IsOptional = new RaceActorCapabilities(0x01)   // failure to create/initialize/start is not critical
  final val IsAutomatic = new RaceActorCapabilities(0x02)  // requires no interaction at any time

  //--- time capabilities
  final val SupportsSimTime = new RaceActorCapabilities(0x0100)       // does not have to run in real time
  final val SupportsSimTimeReset = new RaceActorCapabilities(0x0200)  // can adjust actor local time on-the-fly
  final val SupportsPauseResume = new RaceActorCapabilities(0x0400)   // can be paused/resumed
  final val SupportsDiscreteTime = new RaceActorCapabilities(0x0800)  // supports non-linear time advance

  //... and more to follow

  final val AllCapabilities = new RaceActorCapabilities(0xffffffffffffffffL)
  final val NoCapabilities = new RaceActorCapabilities(0)
}

/**
  * a bit set that contains capabilities of a RaceActor
  */
case class RaceActorCapabilities (caps: Long) extends AnyVal {
  import RaceActorCapabilities._

  def has(rac: RaceActorCapabilities) = (caps & rac.caps) != 0
  def lacks(rac: RaceActorCapabilities) = (caps & rac.caps) == 0

  @inline def add(rac: RaceActorCapabilities) = new RaceActorCapabilities(caps | rac.caps)
  @inline def + (rac: RaceActorCapabilities) = add(rac)
  @inline def remove(rac: RaceActorCapabilities) = new RaceActorCapabilities(caps & ~rac.caps)
  @inline def - (rac: RaceActorCapabilities) = remove(rac)

  @inline def union (rac: RaceActorCapabilities) = new RaceActorCapabilities( caps | rac.caps)
  @inline def intersection (rac: RaceActorCapabilities) = new RaceActorCapabilities( caps & rac.caps)

  @inline def isOptional = (caps & IsOptional.caps) != 0
  @inline def isAutomatic = (caps & IsAutomatic.caps) != 0
  @inline def supportsSimTime = (caps & SupportsSimTime.caps) != 0
  @inline def supportsSimTimeReset = (caps & SupportsSimTimeReset.caps) != 0
  @inline def supportsPauseResume = (caps & SupportsPauseResume.caps) != 0
  @inline def supportsDiscreteTime = (caps & SupportsDiscreteTime.caps) != 0

  def toLong: Long = caps

  override def toString = {
    val sb = new StringBuffer("{")
    @inline def append (s: String) = {
      if (sb.length > 1) sb.append(',')
      sb.append(s)
    }

    if (isOptional) sb.append("IsOptional")
    if (isAutomatic) append("IsAutomatic")
    if (supportsSimTime) append("SupportsSimTime")
    if (supportsSimTimeReset) append("SupportsSimTimeReset")
    if (supportsPauseResume) append("SupportsPauseResume")
    if (supportsDiscreteTime) append("SupportsDiscreteTime")
    sb.append('}')
    sb.toString
  }
}
