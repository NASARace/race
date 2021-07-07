/*
 * Copyright (c) 2021, United States Government, as represented by the
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
import sbt.complete._
import sbt.complete.DefaultParsers._

/**
  * task to run the Rust's 'cargo' build command in the current project
  *
  * Note this requires a Rust tool chain to be installed, e.g. via 'rustup' - see https://rustup.rs/
  */
object CargoTask {

  val pathParser: Parser[String] = {
    token(any.* map (_.mkString.strip), "sub-dir to run cargo in")
  }

  def getCargoDir (baseDir: File, subDir: String): Option[File] = {
    var dir = new File(baseDir, subDir)
    if (!dir.isDirectory()){
      dir = new File(baseDir, s"src/main/rust/$subDir")
      if (!dir.isDirectory()) return None
    }
  
    val cargoFile = new File(dir,"Cargo.toml") // this is fixed
    if (cargoFile.isFile()) Some(dir) else None
  }

  def cargoBuild (baseDir: File, subDir: String) = {
    getCargoDir(baseDir, subDir) match {
      case Some(rustDir) =>
        println(s"running 'cargo build' in $rustDir")
        Process("cargo" :: "build" :: Nil, rustDir).!
      case None =>
        println(s"not a cargo (rust) dir: $baseDir/$subDir")
    }
  }

  def cargoBuildRelease (baseDir: File, subDir: String) = {
    getCargoDir(baseDir, subDir) match {
      case Some(rustDir) =>
        println(s"running 'cargo build --release' in $rustDir")
        Process("cargo" :: "build" :: "--release" :: Nil, rustDir).!
      case None =>
        println(s"not a cargo (rust) dir: $baseDir/$subDir")
    }
  }

  def cargoClean (baseDir: File, subDir: String) = {
    getCargoDir(baseDir, subDir) match {
      case Some(rustDir) =>
        println(s"running 'cargo clean' in $rustDir")
        Process("cargo" :: "clean" :: Nil, rustDir).!
      case None =>
        println(s"not a cargo (rust) dir: $baseDir/$subDir")
    }
  }
}
