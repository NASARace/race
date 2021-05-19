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

import java.io.{BufferedReader, InputStreamReader, PipedInputStream, PipedOutputStream, PrintStream, PrintWriter}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.matching.Regex

object AutomatedIO {
  val _mux = new Object // we could also use Console as the synchronization object but its methods are not synced either
}
import AutomatedIO._

/**
  * programmatic access to redirected system streams
  */
trait AutomatedIO {

  //--- the streams that have to be set up by concrete type
  def sysOut0: PrintStream // the original System.out (before redirection)

  def sysIn: PrintWriter // writing to (redirected) System.in
  def sysOut: BufferedReader // reading from (redirected) System.out

  def prefix: String // to be provided by concrete type

  //--- reading from /writing to redirected system streams

  def sendInput (input: String): Unit = {
    consumePendingOutput()
    showInput(input)
    sysIn.println(input)
  }

  def expectOutput (outputMatcher: Regex, timeout: Duration = 2.seconds): Boolean = {
    val matcher = Future {
      var outputSeen = false
      var done = false

      while (!done){
        val output = readOutput
        if (output == null)  {
          done = true
        } else {
          if (outputMatcher.findFirstIn(output) != None) {
            showMatch(outputMatcher.toString)
            outputSeen = true
            done = true
          }
        }
      }

      outputSeen
    }

    try {
      Await.result(matcher, timeout)
    } catch {
      case tox:TimeoutException => throw new AssertionError(s"timeout while waiting for $outputMatcher")
    }
  }

  def matchIOSequence (seq: (String,(Regex,Duration))*): Unit = {
    seq.foreach { p =>
      sendInput(p._1)
      expectOutput(p._2._1, p._2._2)
    }
  }

  /**
    * read and display all SUT output that is available at this point without blocking
    * NOTE - this might split lines in case the SUT doesn't output line-by-line in autoflush mode
    */
  def consumePendingOutput(): Unit = {
    // TODO - not particularly efficient
    if (sysOut.ready){
      val sb = new StringBuilder
      while (sysOut.ready()) {
        val c = sysOut.read().toChar
        if (c == '\n') {
          showOutput(sb.toString())
          sb.clear()
        } else {
          sb.append(c)
        }
      }
      if (sb.nonEmpty) {
        showOutput(sb.toString())
      }
    }
  }


  //--- screen output (regardless of current System.out)
  //    note those methods are protected against interleaving but only within the same process

  def show(s: String): Unit = _mux.synchronized {
    sysOut0.print(prefix)

    sysOut0.print(Console.WHITE)
    sysOut0.print("> ")
    sysOut0.println(s)
    sysOut0.print(Console.RESET)
  }

  def showOutput(s: String): Unit = _mux.synchronized {
    sysOut0.print(prefix)
    sysOut0.println(s)
  }

  def showMatch(s: String): Unit = _mux.synchronized {
    sysOut0.print(prefix)

    sysOut0.print(Console.WHITE)
    sysOut0.print("> matched \"")
    sysOut0.print(s)
    sysOut0.println('"')
    sysOut0.print(Console.RESET)
  }

  def showInput(s: String): Unit = _mux.synchronized {
    sysOut0.print(prefix)
    sysOut0.print(Console.WHITE)
    sysOut0.print("> enter \"")
    sysOut0.print(s)
    sysOut0.println('"')
    sysOut0.print(Console.RESET)
  }

  //--- internals

  def readOutput: String = {
    val output = stripAnsiCtl(sysOut.readLine)
    if (output != null) {
      showOutput(output)
    }

    output
  }

  final val termCtlPattern = """\033\[\d+m""".r

  def stripAnsiCtl (s: String): String = {
    if (s != null)
      termCtlPattern.replaceAllIn(s, "")
    else s
  }

}

/**
  * a trait that replaces system streams to automate output/action pairs in code sections
  * used for testing purposes
  */
trait AutomatedConsole extends AutomatedIO {

  val sysOut0 = System.out
  val sysIn0 = System.in

  val posIn = new PipedOutputStream
  val pisIn = new PipedInputStream(posIn)
  val sysIn = new PrintWriter(posIn,true)

  val pisOut = new PipedInputStream
  val posOut = new PipedOutputStream(pisOut)
  val sysOut = new BufferedReader(new InputStreamReader(pisOut))

  var level = 0

  /**
    * execute provided thunk with redirected, automated system streams
    */
  def executeAutomated (f: =>Unit): Unit = {
    try {
      if (level == 0) {
        System.setIn(pisIn)
        System.setOut(new PrintStream(posOut,true))
      }
      level += 1

      f

    } finally {
      level -= 1
      if (level == 0) {
        System.setIn(sysIn0)
        System.setOut(sysOut0)
      }
    }
  }
}
