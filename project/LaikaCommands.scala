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

import laika.sbt.LaikaPlugin.autoImport._
import laika.tree.Elements._
import sbt._
import sbt.{Command, FileFilter, Project}
import sbt.Keys._

object LaikaCommands {

  def manualCmd (state: State): State = {
    val extracted = Project extract state
    import extracted._
    runTask(laikaSite in Laika,
      append(Seq(
        sourceDirectories in Laika := Seq(new File("doc/manual")),
        target in laikaSite := target.value / "doc",
        laikaRawContent := true,
        excludeFilter in Laika := new FileFilter {
          override def accept(file:File): Boolean = Seq("attic", "slides", "articles").contains(file.getName)
        }
      ),state))._1
  }
  def mkManual = Command.command("mkManual") { manualCmd }

  def slidesCmd (state: State): State = {
    val extracted = Project extract state
    import extracted._
    runTask(laikaSite in Laika,
      append(Seq(
        sourceDirectories in Laika := Seq(new File("doc/slides")),
        target in laikaSite := target.value / "doc" / "slides",
        laikaRawContent := true,
        laikaSiteRenderers += laikaSiteRenderer { out =>
          { case Section(hdr@Header(2, _, _), content, _) => out << "</div><div class=\"slide\">" << hdr << content }
        }
      ), state))._1
  }
  def mkSlides = Command.command("mkSlides") { slidesCmd }

  def articlesCmd (state: State): State = {
    val extracted = Project extract state
    import extracted._
    runTask(laikaSite in Laika,
      append(Seq(
        sourceDirectories in Laika := Seq(new File("doc/articles")),
        target in laikaSite := target.value / "doc" / "articles"
      ),state))._1
  }
  def mkArticles = Command.command("mkArticles") { articlesCmd }

  def mkDoc = Command.command("mkDoc") { state =>
    slidesCmd( manualCmd(state))
  }

  val commands = Seq(mkDoc,mkManual,mkSlides,mkArticles)
}