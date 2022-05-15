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

object TaskSettings {
  
  lazy val tree = taskKey[Unit]("print src/ tree of this project")
  lazy val editor = settingKey[String]("editor")
  lazy val edit = inputKey[Unit]("edit file")
  lazy val pwd = taskKey[String]("print directory of current project")
  lazy val sh = inputKey[Unit]("execute shell command from within SBT")

  lazy val mkSite = taskKey[Unit]("compile manual website")
  lazy val mkSlides = taskKey[Unit]("compile slides")

  lazy val avroCompileSchemaCmd = settingKey[String]("command to compile avro schema")
  lazy val avroSourceDirectory = settingKey[String]("source root for Avro files")
  lazy val avroTargetDirectory = settingKey[String]("target root for Avro generated files")
  lazy val avroCompileSchemas = taskKey[Unit]("compile avro schemas")

  lazy val make = taskKey[Unit]("run make to build native components")
  lazy val makeClean = taskKey[Unit]("run make to cleanup native components")
  lazy val makeCmd = settingKey[String]("command to run make")
  lazy val makefile = settingKey[String]("name of Makefile")

  lazy val cargoBuild = inputKey[Unit]("run 'cargo build' in current project")
  lazy val cargoBuildRelease = inputKey[Unit]("run 'cargo build --release' in current project")
  lazy val cargoClean = inputKey[Unit]("run 'cargo clean' in current project")

  lazy val removeIvyLocal = taskKey[Unit]("delete .ivy2/local/<org>")

  lazy val taskSettings = Seq(
    //--- unix tree command (listing what is under current src/)
    tree := TreeTask(sourceDirectory.value),
    tree / aggregate := false,

    //--- edit file (input example, not functional yet)
    edit := EditTask( editor.value, EditTask.pathParser.parsed),
    //connectInput in edit := true,
    editor := EditTask.defaultEditor,
    edit / aggregate := false,

    //--- print working dir
    pwd := withReturn(baseDirectory.value.getPath)(println),
    pwd / aggregate := false,

    //--- execute shell cmds from within SBT
    sh := ShTask(spaceDelimited("<arg>").parsed),
    sh / aggregate := false,

    //--- compile Avro schemas
    avroCompileSchemaCmd := AvroTask.defaultCompileCmd,
    avroSourceDirectory := AvroTask.defaultSourceDirectory,
    avroTargetDirectory := AvroTask.defaultTargetDirectory,
    avroCompileSchemas := AvroTask.compileSchemas(avroCompileSchemaCmd.value,
                                                  baseDirectory.value / avroSourceDirectory.value,
                                                  baseDirectory.value / avroTargetDirectory.value),

    //--- Make task (native code compilation/build)
    makeCmd := MakeTask.defaultMakeCmd,
    makefile := MakeTask.defaultMakefile,
    make := MakeTask.makeAll(makeCmd.value,baseDirectory.value,makefile.value),
    makeClean := MakeTask.makeClean(makeCmd.value,baseDirectory.value,makefile.value),

    //--- cargo tasks (Rust build tool)
    cargoBuild := CargoTask.cargoBuild( baseDirectory.value, CargoTask.pathParser.parsed),
    cargoBuildRelease := CargoTask.cargoBuildRelease( baseDirectory.value, CargoTask.pathParser.parsed),
    cargoClean := CargoTask.cargoClean( baseDirectory.value, CargoTask.pathParser.parsed),

    removeIvyLocal := {
      RemoveIvyLocal.removeIvyLocal()
    },
    publishLocal := {
      removeIvyLocal.value
      publishLocal.value
    },

    //--- Laika wrappers

    //--- test settings
    Test / testOptions ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oD"),  // print times
      Tests.Argument(TestFrameworks.ScalaTest, "-oS"),  // short stack traces

      Tests.Argument(TestFrameworks.ScalaTest, "-u", target.value + "/test-reports"),
      Tests.Argument(TestFrameworks.ScalaTest, "-h", target.value + "/test-reports")  // generate html reports
    )
  )
}
