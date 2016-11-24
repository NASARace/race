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

import java.io.File

import sbt._
import Keys._

// NOTE - using macros such as "++=" and "+=" is dangerous since it
// uses a implicit (context dependent) Append.Value(s) argument

object PluginSettings {

  // some of these are just examples for now, to show how to initialize plugin settings
  // without proliferating build.sbt with plugin configs


  //----------------------------------------------------------------------------------
  // laika from https://github.com/planet42/Laika
  // adds laika:site and laika:clean to generate web site and/pr PDFs
  import laika.sbt.LaikaSbtPlugin.LaikaPlugin
  import laika.sbt.LaikaSbtPlugin.LaikaKeys._
  val laikaSettings = LaikaPlugin.defaults ++ Seq(
    sourceDirectories in Laika := Seq(file("doc/manual")),
    target in Laika := target.value / "doc",
    rawContent in Laika := true,
    includePDF in Laika := false,
    includeAPI in Laika := false, // not yet
    excludeFilter in Laika := new FileFilter {
      override def accept(file:File): Boolean = Seq("attic","slides").contains(file.getName)
    },
    aggregate in Laika := false
  )

  //----------------------------------------------------------------------------------
  // sbtstats from https://github.com/orrsella/sbt-stats
  import com.orrsella.sbtstats._
  import com.orrsella.sbtstats.StatsPlugin._
  val sbtStatsSettings = Seq(
    StatsPlugin.statsAnalyzers := Seq(new FilesAnalyzer,new LinesAnalyzer),
    aggregate in statsProject := false  // it can't handle RootProjects and we want a per project breakdown anyways
  )

  //----------------------------------------------------------------------------------
  // sbt-scalariform: https://github.com/daniel-trinh/sbt-scalariform

  import com.typesafe.sbt.{SbtScalariform => SSF}
  val scalariformSettings = SSF.defaultScalariformSettings
  // SSF.scalariformSettings would automatically reformat on each compile

  //----------------------------------------------------------------------------------
  // sbt-dependency-graph: https://github.com/jrudolph/sbt-dependency-graph
  //val depGraphSettings = net.virtualvoid.sbt.graph.Plugin.graphSettings


  //----------------------------------------------------------------------------------
  // multi-jvm test support: https://github.com/sbt/sbt-multi-jvm
  // (has to be added explicitly for respective programs)

  import sbt.{Test,Tests,Compile}
  import sbt.Keys._
  import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.{MultiJvm,scalatestOptions}

  val multiJVMSettings = Seq(
    //compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    compile in MultiJvm := {(compile in MultiJvm) triggeredBy (compile in Test)}.value,
    executeTests in Test := {
      val testResults = (executeTests in Test).value
      val multiNodeResults = (executeTests in MultiJvm).value
      val overall = (if (testResults.overall.id < multiNodeResults.overall.id) multiNodeResults else testResults).overall
      Tests.Output(overall,
        testResults.events ++ multiNodeResults.events,
        testResults.summaries ++ multiNodeResults.summaries)
    },
    logBuffered in MultiJvm := true,
    scalatestOptions in MultiJvm ++= {(target in Compile)((t: File) => Seq("-u", t.getAbsolutePath + "/test-reports"))}.value,
    scalatestOptions in MultiJvm ++= {(target in Compile)((t: File) => Seq("-h", t.getAbsolutePath + "/test-reports"))}.value,
    parallelExecution in Test := false
  )

  // collect all settings for all active plugins. This goes into build.sbt commonSettings
  val pluginSettings = laikaSettings ++ sbtStatsSettings ++ scalariformSettings //++ depGraphSettings
}