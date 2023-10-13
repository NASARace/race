/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import gov.nasa.race.util.FileUtils
import gov.nasa.race.{Failure, ResultValue, SuccessValue}

import java.io.File
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.sys.process.{Process, ProcessLogger}
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * some external processing function as an abstraction on top of ProcessBuilder/Process
 * this can be implemented as a process or an interface to a native library and also supports async execution
 */
trait ExternalFunction[T] {
  def lastError: Option[String]
  def execSync(): ResultValue[T]

  def exec(): Future[ResultValue[T]] = Future { execSync() }

  // watch out - the provided PF is executed in another thread!
  def exec [U] (pf: PartialFunction[ResultValue[T],U]): Unit = exec().foreach( pf.apply )
}


object ExternalProc {

  private var systemPaths: List[Array[String]] = getSystemPaths

  def getSystemPaths: List[Array[String]] = {
    var list = List.empty[Array[String]]
    list = addPathSpec(System.getenv("PATH"),list)
    list = addPathSpec(System.getProperty("race.path"),list)
    list
  }

  def addPathSpec(pathSpec: String, paths: List[Array[String]]): List[Array[String]] = {
    if (pathSpec != null && pathSpec.nonEmpty) {
      val elems = FileUtils.getDirElements(pathSpec).map(_.getPath)
      if (elems.nonEmpty) elems :: paths else paths
    } else paths
  }

  def findInPaths(fileName: String, paths: List[Array[String]]): Option[File] = {
    paths.foreach { dirs =>
      val f = FileUtils.findExecutableFile(fileName, dirs)
      if (f.isDefined) return f
    }
    None
  }

  /**
   * locate fileName in stack of path specs. Lookup order proceeds from local to global to system environment
   */
  def find (fileName: String, localRacePaths: Option[String], globalRacePaths: Option[String]): Option[File] = {
    var paths = systemPaths
    globalRacePaths.foreach( s=> paths = addPathSpec(s, paths))
    localRacePaths.foreach( s=> paths = addPathSpec(s, paths))

    findInPaths(fileName, paths)
  }
}

/**
 * an external function that is implemented as a child process, i.e. represents the execution of another program
 *
 * instances have to support sanity checks for arguments (all required args provided, arguments have right types etc.)
 *
 * default execution mode is async, i.e. results should be processed in a Future since execution time is a priori unknown
 *
 * execution results are typed. Result values can be derived from arguments (e.g. output file names) or from captured
 * console output.
 *
 * argument setters should support
 *  - arg value type checks
 *  - arg permutation where allowed (similar to named function arguments)
 *  - unique arg prefixes (to prevent multiple occurrences of the same argument type)
 *  - additional fixed position args (e.g. input / output file names as trailing arguments)
 *  - taking/restoring arg snapshots for repeated invocation with common args (e.g. set from config)
 *
 * instances are used in a builder pattern:
 *   val cmd = MyCmd( executableFile).setArg1(a1).setArg2(a2)
 *   ...
 *   cmd.exec {
 *     case SuccessValue(result) => ...
 *     case Failure(err) => ..
 *   }
 */
trait ExternalProc[T] extends ExternalFunction[T] {

  val prog: File // the executable itself - mandatory (note we use a File here so that we can support abs/rel paths)

  // note these are both in reverse
  protected var savedArgs: List[String] = Nil
  protected var args: List[String] = Nil // we keep this variable so that we can use builder patterns and/or subtype specific construction

  def cwd: Option[File] = None // optional working dir to change to before executing child process
  def env: Seq[(String,String)] = Seq.empty  // optional child proc environment vars

  def saveArgs(): this.type = {
    savedArgs = args
    this
  }

  def resetToSavedArgs(): this.type = {
    args = savedArgs
    lastError = None
    this
  }

  def reset(): this.type = {
    args = Nil
    savedArgs = Nil
    lastError = None
    this
  }

  // note this prepends args
  protected def addArg (a: String): this.type = {
    args = a :: args
    this
  }

  protected def addArgs (as: String*): this.type = {
    as.foreach( addArg)
    this
  }

  protected def addUniqueArg (a: String, prefix: String): this.type = {
    if (!args.exists(_.startsWith(prefix))) {
      addArg(a)
    } else throw new RuntimeException(s"multiple occurrences of $prefix arguments not allowed")
  }

  def canRun: Boolean = true // override if we have to check if all required arguments are specified

  // build command from reverse args. Override if we have to add special positional arguments (free filename etc.)
  protected def buildCommand: StringBuilder = {
    args.foldRight(new StringBuilder(prog.getPath))( (a,sb) => sb.append(' ').append(a))
  }

  protected var log: Option[ProcessLogger] = None
  protected var outputBuffer = new StringBuilder
  protected var logFile: Option[File] = None

  def ignoreConsoleOutput(): this.type = {
    log = Some( ProcessLogger( line => {} ))
    this
  }

  def captureConsoleOutputToString(): this.type = {
    log = Some( ProcessLogger( line => { outputBuffer.append(line).append('\n') } ))
    this
  }

  def captureConsoleOutputToFile(file: File): this.type = {
    logFile = Some(file)
    log = Some( ProcessLogger(file))
    this
  }

  //--- output accessors
  def consoleOutputAsString: String = outputBuffer.toString()
  def consoleOutputAsFile: Option[File] = logFile

  var lastError: Option[String] = None

  def successExitCode: Int = 0 // default is standard Java/Posix convention

  // only to be called in case exitCode is successExitCode
  protected def getSuccessValue: T

  // only to be called in case exitCode is NOT a successExitCode
  protected def getErrorMessage(exitCode: Int): String = s"$prog returned error code $exitCode"

  override def execSync(): ResultValue[T] = {
    if (canRun) {
      try {
        lastError = None
        outputBuffer.clear()

        val cmd = buildCommand.toString()

        val exitCode = log match { // this blocks until child process returns
          case Some(logger) => Process( cmd, cwd, env: _*).!(logger)
          case None => Process(cmd, cwd, env: _*).!
        }

        if (exitCode == successExitCode) {
          lastError = None
          SuccessValue(getSuccessValue)
        } else {
          val msg = getErrorMessage(exitCode)
          lastError = Some(msg)
          Failure(msg)
        }
      } catch {
        case x: Throwable =>
          val msg = x.getMessage
          lastError = Some(msg)
          Failure(msg)
      }
    } else Failure("not enough arguments")
  }
}
