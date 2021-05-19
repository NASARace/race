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
package gov.nasa.race.test

import java.io.{BufferedReader, InputStreamReader, PrintStream, PrintWriter}
import java.net.Socket
import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * type that defines SUT to run as an external process plus the respective instrumentation (in both the SUT- and
  * the NodeExecutor- process) to control its execution.
  *
  * There are currently two supported sync mechanisms:
  *  - signals 1:N one-time notification of nodes (recipient has to wait before sender signals or signal is lost)
  *  - barriers requires all participating nodes to reach a sync point (essentially a signal with memory)
  */
trait TestNode extends RaceSpec {
  def name: String

  val prefix = s"[${Console.WHITE}$name${Console.RESET}] "

  //--- create SUT and (node- and executor) SUT instrumentation
  def start(exec: NodeExecutor): Process // this executes within the NodeExecutor main thread

  //--- the sync API
  def sendSignal (sig: String, nodes: TestNode*): Unit
  def expectSignal(sig: String, timeout: Duration = 5.seconds): Unit
  def barrier (bar: String, timeout: Duration, nodes: TestNode*): Unit

  def delay (dur: FiniteDuration): Unit = {
    show(s"delay $dur")
    Thread.sleep(dur.toMillis)
  }

  //--- test environment initialization and cleanup
  // these are called from the Executor, before and after the node process starts/terminates
  def beforeExecution(): Unit = {}
  def afterExecution(): Unit = {}

  //--- internals

  def show(s: String): Unit // display console output related to the node instrumentation

  def nodeClasspath: String = {
    // NOTE - this requires "Test / fork := true" in SBT config -> needs our own test configuration
    System.getProperty("java.class.path")

    // alternatively we could get the test-classes dir of the executor object:
    //        getClass.getProtectionDomain.getCodeSource.getLocation
    // plus collect all the ../target/scala-*/classes dirs of all the sub-projects, but that is a blind superset
    // which might cause collisions (although we should not mask classes via the classpath)
  }
}



/**
  * a TestNode that represents a generic SUT with no instrumentation within the SUT process. IO redirection is
  * performed at the executor level when starting the node process, sync instrumentation completely lives within
  * the executor process.
  *
  * While this provides less control over the SUT (only by means of IO redirection) it makes it easier to synchronize
  * and evaluate cross-node properties in the executor
  */
abstract class BlackboxTestNode (val name: String) extends TestNode with AutomatedIO {
  val command: Array[String]

  var nodeOps: BlackboxNodeOps = null

  //--- the input/output streams of the SUT
  var out: PrintWriter  = null // input stream of SUT process
  var in: BufferedReader = null  // output streams of SUT process

  def sysOut0: PrintStream = System.out
  def sysIn: PrintWriter = out
  def sysOut: BufferedReader = in

  def startProcess (cmd: Array[String]): Process = {
    val proc = new ProcessBuilder(cmd.toList.asJava).start()

    out = new PrintWriter(proc.getOutputStream, true)
    in = new BufferedReader( new InputStreamReader(proc.getInputStream))

    proc
  }

  def start(exec: NodeExecutor): Process = {
    val proc = startProcess(command)
    nodeOps = exec.connectToBlackboxTestNode(this)
    proc
  }

  def run(): Unit // provided by concrete type - this controls the SUT process execution from the executor thread

  // we just forward these to the NodeThread
  def sendSignal (sig: String, nodes: TestNode*): Unit = {
    consumePendingOutput()
    show(s"[sending signal '$sig' to nodes: " + nodes.map(_.name).mkString(",") + ']')
    nodeOps.sendSignal(sig,nodes)
  }
  def expectSignal(sig: String, timeout: Duration = 5.seconds): Unit = {
    consumePendingOutput()
    show(s"[wait for signal '$sig']")
    nodeOps.expectSignal(sig, timeout)
    show(s"[received signal '$sig']")
  }
  def barrier (bar: String, timeout: Duration, nodes: TestNode*): Unit = {
    val peerNodeNames = nodes.map(_.name).mkString(",")

    consumePendingOutput()
    show(s"[entering barrier '$bar' with nodes: $peerNodeNames]")
    nodeOps.barrier(bar,timeout,nodes)
    show(s"[leaving barrier '$bar']")
  }
}

/**
  * a TestNode that represents a JAVA SUT. The SUT entry (usually its main() method) is explicitly called from within
  * the node process, which means IO redirection has to happen at the Java level (System.{in,out}) instead of the
  * executor process.
  *
  * JvmTestNodes can use whatever is observable of the SUT, i.e. allow more fine grained control from within the node
  * process but do not support executor specific state (other than signal/barrier) to be used for controlling the SUT
  *
  * TODO - this currently prints the SUT output from within the node process, i.e. simultaneous output by several nodes
  * can overlap. To synchronize we would have to delegate the output to the executor at the cost of increased network
  * traffic / latency
  */
abstract class JvmTestNode (val name: String) extends TestNode with AutomatedConsole {

  var execSock: Option[Socket] = None // control channel to the executor

  // since these are all initialized and checked before we enter the SUT there is no use wrapping them into Options

  //--- the input/output streams of the SUT
  var out: PrintWriter  = null
  var in: BufferedReader = null

  def run(): Unit // provided by concrete type - this starts the SUT and controls its execution in the node process

  def nodeMainClassName: String = {
    val cn = getClass.getName
    if (cn.charAt(cn.length-1) == '$') cn.substring(0,cn.length-1) else cn // JVM doesn't know about companion objects
  }

  // this is executed in the NodeExecutor process, one node at a time
  def start (exec: NodeExecutor): Process = {
    val port = exec.getJvmTestNodePort()

    val proc = new ProcessBuilder().inheritIO().command(
      List(
        "java",
        "-cp", nodeClasspath,
        nodeMainClassName,
        "localhost",
        port.toString
      ).asJava
    ).start()

    exec.connectToJvmTestNode(this)

    proc
  }

  /**
    * override if starting up process takes more time
    * note we don't execute SUT code before connection so this only has to account for static init of the process
    */
  def connectTimeout: FiniteDuration = 5.seconds

  // the main entry for the node process
  final def main (args: Array[String]): Unit = {
    try {
      if (initialize(args)) {
        executeAutomated {
          show("[started]")
          run()
          consumePendingOutput()
          show("[terminated]")
        }
        execSock.foreach(_.close())
        sys.exit(0)

      } else {
        execSock.foreach(_.close())
        sys.exit(1)
      }

    } catch {
      case x:Throwable =>
        //x.printStackTrace()
        failure(x.getMessage)
    }
  }

  def initialize (args: Array[String]): Boolean = {
    if (args.length == 2) {
      try {
        val execHost = args(0)
        val execPort = Integer.parseInt(args(1))
        val sock = new Socket(execHost,execPort)
        execSock = Some(sock)
        in = new BufferedReader( new InputStreamReader(sock.getInputStream))
        out = new PrintWriter(sock.getOutputStream, true)
        true

      } catch {
        case x: Throwable =>
          println(s"test node '$name' initialization error: $x")
          false
      }

    } else {
      println("missing arguments, test node usage: 'java <className> <exeHost> <execPort>'")
      false
    }
  }

  def startAsDaemon (f: =>Unit): Thread = {
    new Thread {
      setDaemon(true)
      start()

      override def run(): Unit = f
    }
  }

  protected def waitForResponse (msgType: String, tag: String, timeout: Duration): Unit = {
    val f = Future[Boolean] {
      val msg = in.readLine
      msg != null && msg.startsWith(msgType) && msg.regionMatches( msgType.length + 1, tag, 0, tag.length)
    }

    try {
      if (!Await.result(f, timeout)) failure(s"node $name got unexpected result while waiting for ${msgType.toLowerCase()} $tag")

    } catch {
      case tox: TimeoutException =>
        failure(s"timeout while waiting for ${msgType.toLowerCase()} $tag")
    }

  }

  def failure(msg: String): Unit = {
    show(s"[failed: $msg]")
    out.println(s"FAILURE $msg")
    execSock.foreach(_.close())
    sys.exit(1)
  }

  //--- the API to synchronize with other nodes

  def sendSignal (sig: String, nodes: TestNode*): Unit = {
    consumePendingOutput()
    show(s"[sending signal '$sig' to nodes: " + nodes.map(_.name).mkString(",") + ']')
    nodes.foreach( n=> out.println(s"SIGNAL $sig ${n.name}"))
  }

  def expectSignal(sig: String, timeout: Duration = 5.seconds): Unit = {
    consumePendingOutput()
    show(s"[wait for signal '$sig']")
    waitForResponse("SIGNAL", sig, timeout)
    show(s"[received signal '$sig']")
  }

  def barrier (bar: String, timeout: Duration, nodes: TestNode*): Unit = {
    if (nodes.nonEmpty) {
      val peerNodeNames = nodes.map(_.name).mkString(",")

      consumePendingOutput()
      show(s"[entering barrier '$bar' with nodes: $peerNodeNames]")
      out.println(s"BARRIER $bar $peerNodeNames")
      waitForResponse("BARRIER", bar, timeout)
      show(s"[leaving barrier '$bar']")
    }
  }

}
