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

import sbt._
import Keys._
import complete.DefaultParsers._
import RaceBuild._

import laika.sbt.LaikaSbtPlugin.LaikaPlugin
import laika.sbt.LaikaSbtPlugin.LaikaKeys._

object TaskSettings {
  
  lazy val tree = taskKey[Unit]("print src/ tree of this project")
  lazy val editor = settingKey[String]("editor")
  lazy val edit = inputKey[Unit]("edit file")
  lazy val pwd = taskKey[String]("print directory of current project")
  lazy val sh = inputKey[Unit]("execute shell command from within SBT")

  lazy val mkSite = taskKey[Unit]("compile manual website")
  lazy val mkSlides = taskKey[Unit]("compile slides")

  lazy val taskSettings = Seq(
    //--- unix tree command (listing what is under current src/)
    tree := TreeTask(sourceDirectory.value),
    aggregate in tree := false,

    //--- edit file (input example, not functional yet)
    edit := EditTask( editor.value, EditTask.pathParser.parsed),
    connectInput in edit := true,
    editor := EditTask.defaultEditor,
    aggregate in edit := false,

    //--- print working dir
    pwd := withReturn(baseDirectory.value.getPath)(println),
    aggregate in pwd := false,

    //--- execute shell cmds from within SBT
    sh := ShTask(spaceDelimited("<arg>").parsed),
    aggregate in sh := false,

    //--- Laika wrappers

    //--- test settings
    testOptions in Test ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oD"),  // print times
      Tests.Argument(TestFrameworks.ScalaTest, "-oS"),  // short stack traces

      Tests.Argument(TestFrameworks.ScalaTest, "-u", target.value + "/test-reports"),
      Tests.Argument(TestFrameworks.ScalaTest, "-h", target.value + "/test-reports")  // generate html reports
    )
  )
}
