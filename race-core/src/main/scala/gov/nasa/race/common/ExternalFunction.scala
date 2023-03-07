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

import gov.nasa.race.{Failure, ResultValue, SuccessValue}

import java.io.File
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

/**
 * an external function that is implemented as a child process, i.e. represents the execution of another program
 */
trait ExternalProc[T] extends ExternalFunction[T] {

  val prog: File // the executable itself - mandatory (note we use a File here so that we can support abs/rel paths)
  var args: Seq[String] = Seq.empty // we keep this variable so that we can use builder patterns and/or subtype specific construction

  def cwd: Option[File] = None // optional working dir to change to before executing child process
  def env: Seq[(String,String)] = Seq.empty  // optional child proc environment vars

  // build command from above elements
  protected def buildCommand: String = (prog.getPath +: args).mkString(" ")

  protected var log: Option[ProcessLogger] = None
  protected var outputBuffer = new StringBuilder
  protected var logFile: Option[File] = None

  def ignoreOutput: this.type = {
    log = Some( ProcessLogger( line => {} ))
    this
  }

  def captureOutputToString: this.type = {
    log = Some( ProcessLogger( line => { outputBuffer.append(line).append('\n') } ))
    this
  }

  def captureOutputToFile (file: File): this.type = {
    logFile = Some(file)
    log = Some( ProcessLogger(file))
    this
  }

  //--- output accessors
  def stringOutput: String = outputBuffer.toString()
  def fileOutput: Option[File] = logFile

  var lastError: Option[String] = None

  def successExitCode: Int = 0 // default is standard Java/Posix convention

  // only to be called in case exitCode is successExitCode
  protected def getSuccessValue: T

  // only to be called in case exitCode is NOT a successExitCode
  protected def getErrorMessage(exitCode: Int): String = s"$prog returned error code $exitCode"

  override def execSync(): ResultValue[T] = {
    try {
      lastError = None
      outputBuffer.clear()

      val exitCode = log match {  // this blocks until child process returns
        case Some(logger) => Process(buildCommand, cwd, env: _*).!(logger)
        case None => Process(buildCommand, cwd, env: _*).!
      }

      if (exitCode == successExitCode){
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
  }

  def reset(): Unit = {
    args = Seq.empty
    lastError = None
  }
}
