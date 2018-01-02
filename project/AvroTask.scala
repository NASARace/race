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

import sbt._
import scala.sys.process._

/**
  * Apache Avro tool support
  * this is mostly to compile Avro *.avsc schemas into corresponding Java classes
  *
  * Note that we are less ambitious than plugins such as sbt-avrohugger since we just
  * re-use the stock Avro tools, which have to be installed separately. The reason is that
  * none of the tools support the latest Avro jar, which is incompatible with the versions
  * those plugins were built on. We rather opt for the latest in runtime support, use plain
  * *.java generated files and put them into the src/main/java tree (analogous to dds IDL
  * support), i.e. we add the generated sources to the repo so that not all users have to
  * install the Avro tools
  */
object AvroTask {
  val defaultCompileCmd = "avro-tools compile schema"
  val defaultSourceDirectory = "src/main/avro"
  val defaultTargetDirectory = "src/main/java"

  def compileSchemas (avroCmd: String, srcRoot: File, tgtRoot: File) = {
    println(s"..compiling Avro schemas under $srcRoot ")
    val schemas = getSchemas(srcRoot)
    if (schemas.nonEmpty) {
      val sb = new StringBuilder(avroCmd)
      schemas.foreach{ f=>
        sb.append(' '); sb.append(f)
      }
      sb.append(' '); sb.append(tgtRoot)
      (sb.toString).!
    } else println("no *.avsc files found")
  }

  def getSchemas(base: File): Traversable[File] = {
    Path.allSubpaths(base).filter(_._2.endsWith(".avsc")).map(_._1)
  }
}
