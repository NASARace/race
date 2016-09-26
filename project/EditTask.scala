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

import sbt.State
import sbt.complete._
import sbt.complete.DefaultParsers._
import sbt._

object EditTask {

  val defaultEditor = System.getenv("EDITOR") match {
    case null => "vi"
    case editor => editor
  }

  // tab completion of available sources would be nice
  val pathParser: Parser[String] = {
    token(any.* map (_.mkString), "<pathName> to edit")
  }

  def apply (editor: String, pathName: String) = {
    println(s"$editor $pathName")
  }
}