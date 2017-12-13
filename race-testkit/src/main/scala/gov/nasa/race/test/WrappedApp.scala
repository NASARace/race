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

package gov.nasa.race.test

import java.io._
import java.util.concurrent.TimeoutException

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.matching._

/**
 * class to run blocks of code with redirected standard IO
 * main use is as a test driver of console applications that cannot be
 * instrumented
 *
 * example:
 * {{{
 *  val app = WrappedApp{
 *    Main.main(args)
 *  }
 *  app.whileExecuting {
 *    app.expectOutput( 5.seconds, "1: exit, 2: continue")
 *    app.sendInput("1")
 *  }
 * }}}
 */
object WrappedApp {
  def apply (action: => Any) = new WrappedApp(action)

  // syntactic sugar to make it more conforming to MultiNode test cases
  def sendInput (app: WrappedApp, input: String) = app.sendInput(input)
  def expectOutput (app: WrappedApp, max: FiniteDuration, outputMatcher: Regex) =
     app.expectOutput(max, outputMatcher)
  def expectOutput (app: WrappedApp, max: FiniteDuration, expected: String) =
    app.expectOutput(max, expected)

  def delay (app: WrappedApp, dur: FiniteDuration) = app.delay(dur)

  private var _isWrappedApp = false
  def isWrappedApp  = _isWrappedApp
}

class WrappedApp (action: => Any) {

  trait Action {
    def execute: Unit
  }

  val sysOut0 = System.out
  val sysIn0 = System.in

  val posIn = new PipedOutputStream
  val pisIn = new PipedInputStream(posIn)
  val sysIn = new PrintStream(posIn)

  val pisOut = new PipedInputStream
  val posOut = new PipedOutputStream(pisOut)
  val sysOut = new BufferedReader(new InputStreamReader(pisOut))

  var expected: String = _
  var appThread: Thread = _
  
  var failureAction: Action = _
  var beforeAction: Action = _
  var whileAction: Action = _
  var afterAction: Action = _

  def onFailure (block: => Any): WrappedApp = {
    failureAction = new Action() { override def execute = block }
    this
  }

  def beforeExecuting (block: => Any): WrappedApp = {
    beforeAction = new Action() { override def execute = block }
    this
  }
  
  def afterExecuting  (block: => Any): WrappedApp = {
    afterAction = new Action() { override def execute = block }
    this
  }

  def whileExecuting (block: => Any): WrappedApp = {
    whileAction = new Action() { override def execute = block }
    this
  }

  def execute = {
    WrappedApp._isWrappedApp = true
    try {
      System.setIn(pisIn)
      System.setOut(new PrintStream(posOut))

      scala.Console.withIn[Any](pisIn) {
        scala.Console.withOut[Any](posOut) {
          appThread = new Thread {
            override def run: Unit = {
              try {
                action
              } finally {
                posOut.close
              }
            }
          }

          if (beforeAction != null) beforeAction.execute
          
          sysOut0.println("[wrapper] starting app thread")
          appThread.start
          Thread.sleep(100)

          if (whileAction != null) whileAction.execute

          processRemainingOutput
          if (afterAction != null) afterAction.execute
        }
      }

      System.setOut(sysOut0)
      System.setIn(sysIn0)
      this

    } catch {
      case x: TimeoutException =>
        throw new AssertionError(s"timeout while waiting for '$expected'")
      case x: IOException =>
        if (expected != null) throw new AssertionError(s"IO error '$x' while waiting for '$expected'")

    } finally {
      if (failureAction != null){
        failureAction.execute
        Thread.sleep(500)
      }

      //stop(appThread) // only API that /could/ work
      //posIn.close
    }
  }

  def expectOutput (max: FiniteDuration, outputMatcher: Regex) = {
    val matcher = Future {
      var outputSeen = false

      while (!outputSeen){
        val output = readOutput

        if (outputMatcher.findFirstIn(output) != None) {
          expected = null
          sysOut0.println(s"[wrapper] matched expected '$outputMatcher'")
          outputSeen = true
        }
      }
    }

    expected = outputMatcher.toString
    Await.result(matcher, max)
  }

  def expectOutput (max: FiniteDuration, expectedOutput: String) = {
    val matcher = Future {
      var outputSeen = false

      while (!outputSeen){
        val output = readOutput

        if (output == expectedOutput) {
          expected = null
          sysOut0.println(s"[wrapper] matched '$expectedOutput'")
          outputSeen = true
        }
      }
    }

    expected = expectedOutput
    Await.result(matcher, max)
  }

  def readOutput: String = {
    val output = stripAnsiCtl(sysOut.readLine)
    if (output == null){
      throw new IOException("output pipe closed")
    }

    sysOut0.println(s"[wrapper] got: '$output'")
    output
  }

  def sendInput (input: String) = {
    sysOut0.println(s"[wrapper] sending: '$input'")
    sysIn.println(input)
  }

  def delay (dur: FiniteDuration) = {
    sysOut0.println(s"[wrapper] delay $dur")
    Thread.sleep(dur.toMillis)
  }

  final val termCtlPattern = """\033\[\d+m""".r
  def stripAnsiCtl (s: String): String = {
    if (s != null)
      termCtlPattern.replaceAllIn(s, "")
    else s
  }

  @tailrec
  final def processRemainingOutput: Unit = {
    val output = stripAnsiCtl(sysOut.readLine)
    if (output != null){
      sysOut0.println(s"[wrapper] got: '$output'")
      processRemainingOutput
    }
  }
}
