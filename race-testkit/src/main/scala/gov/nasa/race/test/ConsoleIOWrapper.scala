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

/**
 * a utility class to run threads with redirected IO, using a partial function
 * to define which outputs should trigger which actions.
 *
 * Usage:
 * {{{
 * ConsoleIOWrapper {
 *   SomeApp.main(args)
 * }.onOutput {
 *   case  "1: exit, 2: continue" => sendInput("1")
 * }.run
 * }}}
 */

object ConsoleIOWrapper {
  case class Input (line: String)

  def apply (action: => Any) = new ConsoleIOWrapper(action)
  def wrap (action: => Any) =  new ConsoleIOWrapper(action)
  def sendInput (line: String) = Input(line)
}

class ConsoleIOWrapper (action: => Any) {
  final val termCtlPattern = """\033\[\d+m""".r

  private var optOp: Option[PartialFunction[String,Any]] = None


  def stripTermCtl (s: String): String = {
    termCtlPattern.replaceAllIn(s, "")
  }

  private def processOutput (input: String, sysIn: PrintStream, sysOut0: PrintStream) = {
    if (input != null) {
      val s = stripTermCtl(input)
      sysOut0.println( s"""got: "$s" """)

      for (op <- optOp) {
        if (op.isDefinedAt(s)) {
          op(s) match {
            case ConsoleIOWrapper.Input(response) =>
              sysOut0.println( s""" => stdin("$response")""")
              sysIn.println(response)
            case x =>
              sysOut0.println(s" => $x")
          }
        } else {
          sysOut0.println(" => ignored")
        }
      }
    }
  }

  def onOutput (pf: PartialFunction[String,Any]): ConsoleIOWrapper = {
    optOp = Some(pf)
    this
  }

  def runJava: Unit = run(true)

  def run: Unit = run(false)

  private def run (useGlobalRedirect: Boolean): Unit  = {
    val sysOut0 = System.out
    val sysIn0 = System.in

    val posIn = new PipedOutputStream
    val pisIn = new PipedInputStream(posIn)
    val sysIn = new PrintStream(posIn)

    val pisOut = new PipedInputStream
    val posOut = new PipedOutputStream(pisOut)
    val sysOut = new BufferedReader(new InputStreamReader(pisOut))

    try {
      if (useGlobalRedirect) {
        System.setIn(pisIn)
        System.setOut(new PrintStream(posOut))
      }

      scala.Console.withIn[Any](pisIn) {
        scala.Console.withOut[Any](posOut) {
          val thread = new Thread {
            override def run: Unit = {
              try {
                action
              } finally {
                posOut.close
              }
            }
          }
          thread.start
          Thread.sleep(100)

          while (thread.isAlive){
            processOutput(sysOut.readLine, sysIn, sysOut0)
          }
          sysOut0.println("done.")
        }
      }

      if (useGlobalRedirect){
        System.setOut(sysOut0)
        System.setIn(sysIn0)
      }
    } finally {
      posIn.close
    }
  }
}
