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
import java.util.Locale
import scala.sys.process._

/**
  * simple "sh ..." task that executes something in the underlying shell from within SBT
  * based on (https://github.com/melezov/xsbt-sh/blob/master/XsbtShPlugin.scala)
  * other than as a task example we add this locally to avoid another resolver
  */
object ShTask {

  sealed abstract class OS(val execPrefix: String*)
  case object Windows extends OS("cmd", "/c")
  case object Unix extends OS()

  lazy val os = sys.props.get("os.name") match {
    case Some(x) if x.toLowerCase(Locale.ENGLISH) contains "windows" => Windows
    case _ => Unix
  }

  def apply (args: Seq[String]) = (os.execPrefix ++ args).!
}