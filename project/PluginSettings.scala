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
import laika.markdown.github.GitHubFlavor

// NOTE - using macros such as "++=" and "+=" is dangerous since it
// uses a implicit (context dependent) Append.Value(s) argument

object PluginSettings {

  // some of these are just examples for now, to show how to initialize plugin settings
  // without proliferating build.sbt with plugin configs


  //----------------------------------------------------------------------------------
  // laika from https://github.com/planet42/Laika
  // adds laika:site and laika:clean to generate web site and/pr PDFs
  import laika.sbt.LaikaPlugin
  import laika.sbt.LaikaPlugin.autoImport._
  val laikaSettings = LaikaPlugin.projectSettings ++ Seq(
    laikaExtensions := Seq(
      GitHubFlavor
    ),
    Laika / sourceDirectories := Seq(file("doc/manual")),
    Laika / target := target.value / "doc",
    laikaIncludePDF := false,
    laikaIncludeAPI := false, // not yet
    Laika / excludeFilter := new FileFilter {
      override def accept(file:File): Boolean = Seq("attic","slides").contains(file.getName)
    },

    laikaConfig := LaikaConfig.defaults.withRawContent, // this would be the new reference

    Laika / aggregate := false
  )

  //----------------------------------------------------------------------------------
  // sbtstats from https://github.com/orrsella/sbt-stats
  //  import com.orrsella.sbtstats._
  //  import com.orrsella.sbtstats.StatsPlugin.autoImport._
  //  val sbtStatsSettings = Seq(
  //    statsAnalyzers := Seq(new FilesAnalyzer,new LinesAnalyzer),
  //    aggregate in statsProject := true
  //  )

  //----------------------------------------------------------------------------------
  // sbt-scalariform: https://github.com/daniel-trinh/sbt-scalariform

  // import com.typesafe.sbt.{SbtScalariform => SSF}
  // val scalariformSettings = SSF.defaultScalariformSettings
  // SSF.scalariformSettings would automatically reformat on each compile

  //----------------------------------------------------------------------------------
  // sbt-dependency-graph: https://github.com/jrudolph/sbt-dependency-graph
  //val depGraphSettings = net.virtualvoid.sbt.graph.Plugin.graphSettings



  import SonatypeSettings._

  // collect all settings for all active plugins. This goes into build.sbt commonSettings
  val pluginSettings = laikaSettings ++
                       sonatypeSettings
  // ++ sbtStatsSettings
  // ++ scalariformSettings
  // ++ depGraphSettings
}