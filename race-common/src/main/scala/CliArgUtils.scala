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

package gov.nasa.race.common

import java.io.File
import scopt._
import FileUtils._
import NetUtils._

/**
  * shared command line argument utilities
  * based on and extending the scopt library
  */
object CliArgUtils {

  /**
    * an option type that knows how to parse itself
    */
  trait ParseableOption[A] {
    def parser: OptionParser[A]  // to be provided by concrete type

    def check: Boolean = true

    def parse (cliArgs: Array[String]): Option[A] = {
      val p = parser
      p.parse(cliArgs,this.asInstanceOf[A]) match {
        case None => p.showUsageAsError(); None
        case opt@Some(_) => if (check) opt else None
      }
    }

    def checkExistingFileOption(optFile: Option[File], msg: String): Boolean = {
      optFile match {
        case Some(file) =>
          if (file.isFile) {
            true
          } else {
            ConsoleIO.printlnErr(s"$file not found")
            false
          }
        case None =>
          ConsoleIO.printlnErr(s"$msg not specified")
          false
      }
    }
    def checkExistingFile(file: File): Boolean = {
      if (file.isFile) {
        true
      } else {
        ConsoleIO.printlnErr(s"$file not found")
        false
      }
    }
  }

  /**
    * self trait with common checker functions for scopt.OptionParsers
    */
  trait OptionChecker { self: OptionParser[_] =>
    def checkFile(f: File): Either[String, Unit] = {
      existingNonEmptyFile(f) match {
        case Some(file) => success
        case None => failure(s"file does not exist or is empty: $f")
      }
    }

    def checkDir(d: File): Either[String, Unit] = {
      if (d.isDirectory) success else failure(s"directory not found: $d")
    }

    def checkForwards(seq: String): Either[String, Unit] = {
      seq match {
        case PortHostPortRE(_, _, _) => // pass
        case s => return failure(s"not a valid port:host:port forward spec: $s")
      }
      success
    }
  }
}
