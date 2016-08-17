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

import sbt.Keys._
import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.PathList

/**
  * settings related to sbt-assembly (executable jar distribution)
  *
  * NOTE - this does not work yet for WorldWind because of native libs
  */

object Assembly {
  lazy val taskSettings = Seq(

    test in assembly := {},
    mainClass in assembly := Some("gov.nasa.race.ConsoleMain"),
    assemblyMergeStrategy in assembly := {
      // skip all Windows,Unix shared libs from WorldWind
      case PathList(ps @ _*) if ps.last endsWith ".dll" => MergeStrategy.discard
      case PathList(ps @ _*) if ps.last endsWith ".so" => MergeStrategy.discard
      case PathList("META-INF","MANIFEST.MF") => MergeStrategy.rename
      case other => MergeStrategy.first
    }
  )
}