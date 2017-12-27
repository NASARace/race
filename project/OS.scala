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

/**
  * support for OS-specific build functions
  *
  * OS.local can be used in match expressions such as
  *
  *  OS.build match {
  *    case _:Linux => ...
  *
  */

sealed abstract class OS (val name: String, val arch: String, val version: String)

case class Linux(override val name:String,override val arch:String,override val version:String) extends OS(name,arch,version)
case class OSX(override val name:String,override val arch:String,override val version:String) extends OS(name,arch,version)
case class Windows(override val name:String,override val arch:String,override val version:String) extends OS(name,arch,version)
case class Unknown(override val name:String,override val arch:String,override val version:String) extends OS(name,arch,version)


object OS {
  val build: OS = {
    //--- those are guaranteed by the JVM spec
    val name = sys.props("os.name")
    val arch = sys.props("os.arch")
    val version = sys.props("os.version")

    {
      val osname = name.toLowerCase
      if (osname.startsWith("mac")) OSX(name,arch,version)
      else if (osname.startsWith("linux")) Linux(name,arch,version)
      else if (osname.startsWith("windows")) Windows(name,arch,version)
      else Unknown(name,arch,version)
    }
  }
}