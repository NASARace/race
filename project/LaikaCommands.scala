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

import laika.sbt.LaikaSbtPlugin.LaikaKeys._
import laika.tree.Elements._
import sbt._
import sbt.{Command, FileFilter, Project}
import sbt.Keys._

object LaikaCommands {

  def mkManual = Command.command("mkManual") { state =>
    val extracted = Project extract state
    import extracted._
    runTask(site in Laika,
      append(Seq(
        sourceDirectories in Laika := Seq(new File("doc/manual")),
        target in (Laika,site) := target.value / "doc" / "manual",
        excludeFilter in Laika := new FileFilter {
          override def accept(file:File): Boolean = file.getName == "attic"
        }
      ),state))
    state
  }

  def mkSlides = Command.command("mkSlides") { state =>
    val extracted = Project extract state
    import extracted._
    runTask(site in Laika,
      append(Seq(
        sourceDirectories in Laika := Seq(new File("doc/slides")),
        target in (Laika,site) := target.value / "doc" / "slides",
        siteRenderers in Laika += siteRenderer( out => {
          case Section(hdr@Header(2,_,_),content,_) => out <<|
            "</div><div class=\"slide\">" <<| hdr <<| content
        })
      ),state))
    state
  }

  val commands = Seq(mkManual,mkSlides)
}