package gov.nasa.race.test

import scala.concurrent.duration._
import org.scalatest.flatspec.AnyFlatSpec

//--- JvmTestNodes

object ServerNode extends JvmTestNode("server") {
  def run(): Unit = {
    startAsDaemon {
      ServerApp.main(Array.empty[String])
    }

    expectOutput("^server running".r, 3.seconds)
    barrier("ServerUp", 5.seconds, ClientNode)

    expectSignal("ClientUp", 5.seconds)
    expectOutput("^enter command".r, 2.seconds)
    sendInput("1")  // send message
    expectSignal("ClientDone", 5.seconds)
    sendInput("2") // terminate server
  }
}

object ClientNode extends JvmTestNode("client") {
  def run(): Unit = {
    barrier("ServerUp", 5.seconds, ServerNode)

    startAsDaemon {
      ClientApp.main(Array.empty[String])
    }

    expectOutput("^client running".r, 3.seconds)
    sendSignal("ClientUp", ServerNode)
    expectOutput("^client received message".r, 3.seconds)
    sendSignal("ClientDone", ServerNode)
  }
}

class MultiNodeExecutorSpec extends AnyFlatSpec with RaceSpec {
  "a NodeExecutor" should "execute a test consisting of a server and a client node" in {
    NodeExecutor.execWithin( 30.seconds)(
      ServerNode,
      ClientNode
    )
  }
}

//--- BlackboxTestNodes

class MultiBBNodeExecutorSpec extends AnyFlatSpec with RaceSpec {

  lazy val serverNode: TestNode = new BlackboxTestNode("server") {
    val command = Array("java", "-classpath", nodeClasspath, "gov.nasa.race.test.ServerApp")

    def run(): Unit = {
      expectOutput("^server running".r, 3.seconds)
      barrier("ServerUp", 5.seconds, clientNode)

      expectSignal("ClientUp", 5.seconds)
      expectOutput("^enter command".r, 2.seconds)
      sendInput("1")  // send message
      expectSignal("ClientDone", 5.seconds)
      sendInput("2") // terminate server
    }
  }

  lazy val clientNode: TestNode = new BlackboxTestNode("client") {
    val command = Array("java", "-classpath", nodeClasspath, "gov.nasa.race.test.ClientApp")

    def run(): Unit = {
      barrier("ServerUp", 5.seconds, serverNode)

      expectOutput("^client running".r, 3.seconds)
      sendSignal("ClientUp", serverNode)
      expectOutput("^client received message".r, 3.seconds)
      sendSignal("ClientDone", serverNode)
    }
  }

  "a NodeExecutor" should "execute a test consisting of blackbox server and a client nodes" in {
    NodeExecutor.execWithin( 30.seconds)(
      serverNode,
      clientNode
    )
  }
}



//----------------------------------------------------------------------------- mockup Java apps

import java.net.{ServerSocket, Socket}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}

object ServerApp {
  def main (args: Array[String]): Unit = {
    println("server running")
    val in = new BufferedReader(new InputStreamReader(System.in))

    val port: Int = 5432
    var listener: ServerSocket = null
    var sock: Socket = null
    var out: PrintWriter = null

    try {
      listener = new ServerSocket(port)
      println(s"server waiting for connection on port $port..")
      sock = listener.accept()
      println("server connected")
      out = new PrintWriter(sock.getOutputStream(),true)

      var cmd: String = ""
      do {
        println("enter command (1=send message, 2=terminate): ")
        cmd = in.readLine()
        if (cmd.equals("1")) {
          val msg = "Hi there"
          println(s"server sending message: $msg")
          out.println(msg)
        }

      } while (!cmd.equals("2"))

    } finally {
      println("server terminating")
      if (out != null) out.close()
      if (sock != null) sock.close()
      if (listener != null) listener.close()
    }
  }
}

object ClientApp {
  def main (args: Array[String]): Unit = {
    println("client running")

    val host: String = "localhost"
    val port: Int = 5432
    var sock: Socket = null
    var in: BufferedReader = null

    try {
      sock = new Socket(host,port)
      println(s"client connected to $host:$port")
      in = new BufferedReader( new InputStreamReader(sock.getInputStream()))

      println("client waiting for message..")
      val msg = in.readLine()
      println(s"client received message '$msg'")

    } finally {
      println("client terminating")
      if (in != null) in.close()
      if (sock != null) sock.close()
    }
  }
}