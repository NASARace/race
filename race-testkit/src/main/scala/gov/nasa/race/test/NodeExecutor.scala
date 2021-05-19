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

import gov.nasa.race.{ifSome, Failure => RaceFailure, Result => RaceResult, Success => RaceSuccess}
import org.scalatest.Assertion
import org.scalatest.Assertions.fail

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.lang.{Process => JProcess}
import java.net.{ServerSocket, Socket}
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

object NodeExecutor {
  var nextPort: Int = 42000

  def execWithin (dur: Duration)(nodes: TestNode*): Unit = {
    nextPort += 1
    new NodeExecutor(nextPort).execWithin(dur,nodes)
  }
}

trait BlackboxNodeOps {
  def sendSignal (sig: String, nodes: Seq[TestNode]): Unit
  def expectSignal(sig: String, timeout: Duration = 5.seconds): Unit
  def barrier (bar: String, timeout: Duration, nodes: Seq[TestNode]): Unit
}

/**
  * object that starts and synchronizes a number of child processes running TestNodes
  *
  * the reason why we don't directly start and monitor the SUT processes with redirected streams but go through
  * the TestNode wrappers is that we want a side channel to synchronize the processes. If those are all interactively
  * controlled by stdin and observable through stdout this would not be required, but by using TestNodes we can
  * extend this towards in-SUT-process control (for Java apps). We can still do the direct process from within
  * specialized TestNodes.
  *
  * referencing the SUT code from within the TestNode also makes sure we pick up the correct classpath from
  * the build environment
  *
  * note that we minimize external dependencies here in order to avoid mixing test harness with SUT failures. This
  * especially includes avoiding RACE dependencies
  */
class NodeExecutor (port: Int) extends Assertion {

  /**
    * the node instrumentation endpoint running within the executor process
    */
  abstract class NodeThread extends Thread {
    def name: String
    def wakeupWithSignal (sig: String): Unit
    def passBarrier (bar: String): Unit
  }

  /**
    * a NodeThread that is the executor endpoint for a running JvmTestNode process, using a socket
    * to synchronize.
    */
  class JvmNodeThread (node: JvmTestNode, sock: Socket) extends NodeThread {
    //--- those are the control streams to/from the node process
    val toNode = new PrintWriter( sock.getOutputStream, true)
    val fromNode = new BufferedReader( new InputStreamReader(sock.getInputStream))

    def name = node.name

    setDaemon(true)

    override def run(): Unit = {
      try {
        while (!terminate) {
          val msg = fromNode.readLine()
          if (msg == null) { // node process closed the connection
            println(s"-- executor disconnected from node ${node.name}")
            return

          } else {
            processNodeMessage(msg)
          }
        }
      } finally {
        removeNodeThread(this)
      }
    }

    def processNodeMessage (msg: String): Unit = {
      if (msg.nonEmpty) {
        val tokens = msg.split(" ")
        if (tokens.nonEmpty) {
          tokens(0) match {
            case "BARRIER" => enterBarrier(tokens(1), name, tokens.slice(2, tokens.length).toIndexedSeq)
            case "SIGNAL" => signalNodes(tokens(1), tokens.slice(2, tokens.length).toIndexedSeq)
            case "FAILURE" => nodeFailed(name, msg.substring(tokens(0).length))
          }
        }
      }
    }

    //--- the interface towards the executor
    def wakeupWithSignal (sig: String): Unit = toNode.println(s"SIGNAL $sig")
    def passBarrier (bar: String): Unit = toNode.println(s"BARRIER $bar")
  }

  class BlackboxNodeThread (node: BlackboxTestNode) extends NodeThread with BlackboxNodeOps {
    private val lock = new Object() // to block while waiting for signals
    private var expectedSignal: String = null // name of signal we are blocked on, protected by lock
    private var expectedBarrier: String = null

    override def name: String = node.name

    override def run(): Unit = {
      node.run()
      node.show("[terminated]")
      node.consumePendingOutput()
      removeNodeThread(this)
    }

    //--- called from within node.run()

    def sendSignal (sig: String, nodes: Seq[TestNode]): Unit = signalNodes( sig, nodes.map(_.name))

    def expectSignal(sig: String, timeout: Duration = 5.seconds): Unit = {
      lock.synchronized {
        expectedSignal = sig
        while (expectedSignal != null) {
          lock.wait(timeout.toMillis)
        }
      }
    }

    def barrier (bar: String, timeout: Duration, nodes: Seq[TestNode]): Unit = {
      lock.synchronized {
        expectedBarrier = bar
        enterBarrier(bar,name,nodes.map(_.name)) // this might reset expectedBarrier if all other nodes have already reached it

        while (expectedBarrier != null) {
          lock.wait(timeout.toMillis)
        }
      }
    }


    //--- called from other executor node threads

    override def wakeupWithSignal(sig: String): Unit = {
      lock.synchronized {
        if (sig == expectedSignal) {
          expectedSignal = null
          lock.notify()
        }
      }
    }

    override def passBarrier(bar: String): Unit = {
      lock.synchronized {
        if (bar == expectedBarrier) {
          expectedBarrier = null
          lock.notify()
        }
      }
    }
  }

  private var failure: Option[String] = None
  @volatile private var terminate = false

  private val termSignal = new Object // wait for a node process to terminate

  private var serverSocket: Option[ServerSocket] = None // created on-demand if we execute JvmTestNodes

  private val nodeThreads = mutable.Map.empty[String,NodeThread]
  private val nodeProcesses = mutable.Map.empty[String,JProcess]

  private val barriers = mutable.Map.empty[String,mutable.Map[String,mutable.Set[String]]]

  //--- the public API

  def execWithin (timeout: Duration, nodes: Seq[TestNode]): Unit = {
    try {
      nodes.foreach(_.beforeExecution())
      nodes.foreach { n=>
        println(s"-- executor starting node: ${n.name}")
        val proc = n.start(this)
        nodeProcesses += n.name -> proc
      }

      if (!waitForTermination(timeout)) {
        if (!failure.isDefined) failure = Some("nodes did not terminate: " + nodeThreads.keys.mkString(","))
      }

      ifSome(failure){ s=> fail(s) }

    } finally {
      // leave no trace
      nodeProcesses.foreach( p=> ensureTermination(p._1, p._2))
      nodes.foreach(_.afterExecution())
    }
  }

  //--- called from JvmTestNode.startProcess, i.e. in the executor process
  def getJvmTestNodePort(): Int = {
    if (!serverSocket.isDefined) {
      serverSocket = Some(new ServerSocket(port))
    }
    port
  }

  def connectToBlackboxTestNode (node: BlackboxTestNode): BlackboxNodeOps = {
    val nt = new BlackboxNodeThread(node)
    nt.start()
    nodeThreads += node.name -> nt
    nt
  }

  // we connect to JvmTestNode processes synchronously (one at a time)
  def connectToJvmTestNode (node: JvmTestNode): Unit = {
    serverSocket.foreach { ssock =>
      val f = Future[Try[Socket]] {
        Success(ssock.accept()) // this blocks until the JvmTestNode process has connected
      }

      try {
        Await.result(f, node.connectTimeout) match {
          case Success(sock) =>
            val nt = new JvmNodeThread(node, sock)
            nt.start()
            nodeThreads += node.name -> nt

          case Failure(x) =>
            throw new AssertionError(s"executor failed to connect to node: ${node.name}")
        }
      } catch {
        case tox: TimeoutException => throw new AssertionError(s"executor timed out waiting to connect to node: ${node.name}")
      }
    }
  }

  // this has to wait for several running nodes that terminate in unspecified order
  def waitForTermination (timeout: Duration): Boolean = {
    val t0 = System.currentTimeMillis
    var remainingMillis: Long = timeout.toMillis

    while (hasLiveNodeThreads && remainingMillis > 0) {
      try {
        termSignal.synchronized( termSignal.wait(remainingMillis))
        remainingMillis -= (System.currentTimeMillis - t0)

      } catch {
        case _: InterruptedException =>
          remainingMillis -= (System.currentTimeMillis - t0)
      }
    }

    terminate = true
    serverSocket.foreach( _.close())

    nodeThreads.isEmpty
  }

  def ensureTermination (nodeName: String, proc: JProcess): Unit = {
    if (proc.isAlive()) {
      println(s"-- waiting for termination of node process: $nodeName")
      if (!proc.waitFor(10, TimeUnit.SECONDS)) {
        println(s"-- killing node process: $nodeName")
        proc.destroy()
        if (!proc.waitFor(5, TimeUnit.SECONDS)) {
          proc.destroyForcibly()
        }
      }
    }
  }


  protected def hasLiveNodeThreads: Boolean = nodeThreads.synchronized {
    nodeThreads.exists(_._2.isAlive)
  }

  protected def removeNodeThread (t: NodeThread): Unit = nodeThreads.synchronized {
    nodeThreads -= t.name
    if (nodeThreads.isEmpty) {
      termSignal.synchronized(termSignal.notify)
    }
  }

  protected def enterBarrier (bar: String, nodeName: String, peerNodes: Seq[String]): Unit = barriers.synchronized {
    barriers.get(bar) match {
      case Some(e) =>  // not the first entry, check who already entered
        if (!e.contains(nodeName)) { // we can't re-enter the barrier while it isn't processed yet
          val waitSet = mutable.Set.empty[String]
          peerNodes.foreach { p=>
            e.get(p) match {
              case Some(ws) =>  // p already entered - is it waiting on nodeName?
                if (ws.contains(nodeName)) {
                  if (ws.size == 1) passBarrier(bar,p) // all waiters of p joined, let it pass
                  ws -= nodeName
                }

              case None => // p didn't join yet, add it to our waitSet
                waitSet += p
            }
          }

          if (waitSet.isEmpty) passBarrier(bar, nodeName) // all peers had already entered
          e += nodeName -> waitSet

          if (!e.values.exists( _.nonEmpty)) { // all barrier participants have been noticed, remove barrier entry altogether
            barriers -= bar
          }

        } else { // nodeName had already joined previously
          passBarrier(bar, nodeName)
        }

      case None => // first barrier entry, just add
        barriers += bar -> mutable.Map( nodeName -> mutable.Set.from(peerNodes))
    }
  }

  protected def passBarrier (bar: String, n: String): Unit = nodeThreads.synchronized {
    nodeThreads.get(n).foreach(_.passBarrier(bar))
  }

  protected def signalNodes (sig: String, nodes: Seq[String]): Unit = nodeThreads.synchronized {
    nodes.foreach {n=>
      nodeThreads.get(n).foreach(_.wakeupWithSignal(sig))
    }
  }

  protected def nodeFailed (nodeName: String, msg: String): Unit = {
    failure = Some(s"node '$nodeName' failed: $msg")
    println(s"-- executor terminating on failure of node: $nodeName")
    terminate = true
    nodeProcesses.foreach ( np=> ensureTermination(np._1, np._2))
    //throw new AssertionError(s"node '$nodeName' failed: $msg")
  }
}
