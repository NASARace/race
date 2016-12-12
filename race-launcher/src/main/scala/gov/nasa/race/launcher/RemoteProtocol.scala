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

package gov.nasa.race.launcher

import java.io._
import java.lang.management.ManagementFactory
import java.net.SocketException
import java.util
import java.util.Base64

import gov.nasa.race.util.ConsoleIO._
import gov.nasa.race.util.CryptUtils._
import gov.nasa.race.util.ThreadUtils._

/**
  * communication protocol support between RemoteLauncher and RemoteMain
  *
  *         launcher               launchee
  * --------------------------------------------------
  *        !SESSION -------->--------+
  *            +------------<------ LIVE
  *        !LAUNCH --------->------ <launch>
  *          ...
  *         !INSPECT ------->---------+
  *       <display> --------<------ STATE
  *          ...
  *       !TERMINATE ------->------ <exit>
  *
  * TODO this does not yet handle incomplete line transmission (might get blocked in readLine())
  */
trait RemoteProtocolClient {

  final val SESSION = "!SESSION "
  final val LIVE = "LIVE "
  final val LAUNCH = "!LAUNCH "
  final val INSPECT = "!INSPECT "
  final val TERMINATE = "!TERMINATE"

  def serialize(o: Any): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(o)
    oos.close
    bos.toByteArray
  }

  def serializeBase64(o: Any): Array[Byte] = Base64.getEncoder.encode(serialize(o))

  def deserialize[T](data: Array[Byte]): T = {
    val bis = new ByteArrayInputStream(data)
    val ois = new ObjectInputStream(bis)
    val res = ois.readObject
    ois.close
    res.asInstanceOf[T]
  }
  def deserializeAndClear[T](data: Array[Byte]): T = {
    val res = deserialize(data)
    util.Arrays.fill(data, 0.toByte)
    res
  }

  def deserializeBase64[T](base64Data: String): T = deserialize(Base64.getDecoder.decode(base64Data))

  def sendLine(out: OutputStream) = {
    out.write(10)
    out.flush()
  }
}

/**
 * trait to be mixed into the main object that launches remote RACE instances
 * This send the commands to be processed by the launchee
 */
trait RemoteProtocolLauncher extends RemoteProtocolClient {

  val liveRE = """LIVE (.+)""".r
  val stateRE = """STATE (.+)""".r

  def sendSessionToken(out: OutputStream, token: String) = {
    out.write(SESSION.getBytes)
    out.write(token.getBytes)
    sendLine(out)
  }

  // note - this has to be synchronous but should NOT be blocking - we need to detect timeouts
  protected def readStringResponse (br: BufferedReader, timeout: Int)
    (patternMatcher: PartialFunction[String,Option[String]]): Option[String] = {
    if (pollUpTo(timeout, 500)(br.ready)) patternMatcher(br.readLine)
    else None
  }

  def readLiveMessage(br: BufferedReader, timeout: Int): Option[String] = {
    readStringResponse(br,timeout){
      case liveRE(procId) => Some(procId)
      case other => None
    }
  }

  def sendLaunchCmd(out: OutputStream, base64Data: Array[Byte]) = {
    out.write(LAUNCH.getBytes)
    out.write(base64Data)
    sendLine(out)
  }

  def sendInspectCmd(out: OutputStream, topic: String) = {
    out.write(INSPECT.getBytes)
    out.write(topic.getBytes)
    sendLine(out)
  }

  def readStateMessage(br: BufferedReader, timeout: Int): Option[String] = {
    readStringResponse(br, timeout){
      case stateRE(state) => Some(state)
      case other => None
    }
  }

  def sendTerminationCmd(out: OutputStream) = {
    out.write(TERMINATE.getBytes)
    sendLine(out)
  }
}

/**
 * trait to be mixed into remote main objects, i.e. the remote process that is launched.
 * This reads and processes commands sent by a RemotesProtocolLauncher
 */
trait RemoteProtocolLaunchee extends RemoteProtocolClient {
  val sessionRE = """!SESSION (.+)""".r
  val launchRE = """!LAUNCH (.+)""".r
  val inspectRE = """!INSPECT (.+)""".r
  val terminateRE = """!TERMINATE""".r

  val pid = ManagementFactory.getRuntimeMXBean().getName()
  var sessionToken: Option[String] = None

  // the standard launchee dispatcher
  def processLauncherMessages(in: InputStream, out: OutputStream, isAlive: => Boolean): Unit = {
    val br = new BufferedReader(new InputStreamReader(in))

    try {
      // the sequential initialization handshake
      br.readLine() match {
        case sessionRE(token) => processSessionToken(out, token)
        case other => printlnErr(s"expected session token, got: $other"); return
      }
      br.readLine() match {
        case launchRE(data) => processLaunchCmd(data)
        case other => printlnErr(s"expected launch data, got: $other"); return
      }

      // messages we can receive during runtime
      do {
        br.readLine() match {
          case inspectRE(topic) => processInspectCmd(topic)
          case terminateRE() => processTerminateCmd
          case null => return // socket got closed by remote side
          case other => printlnErr(s"unknown RemoteProtocol command: $other")
        }
      } while (isAlive)
    } catch {
      case x: SocketException => // socket got closed by this side
    }
  }

  // this is a automated response that sends our pid followed by the token we got
  def processSessionToken(out: OutputStream, token: String) = {
    sessionToken = Some(token)
    val response = s"$pid/$token"
    out.write(LIVE.getBytes)
    out.write(response.getBytes()) // <pid>@<hostname>/<token>
    sendLine(out)
  }

  //--- methods that have to be provided by the concrete type
  def processLaunchCmd(base64Data: String): Unit
  def processInspectCmd(topic: String): Unit
  def processTerminateCmd: Unit

  // to be called from processLaunchCmd implementation
  def readLaunchData[T](input: String): T = {
    input match {
      case launchRE(base64Data) =>
        getDecryptionCipher(pid.toCharArray) match {
          case Some(launchCipher) => deserializeAndClear(launchCipher.doFinal(base64Decode(base64Data.getBytes)))
          case other => throw new RuntimeException(s"launch data decryption failed")
        }

      case other => throw new RuntimeException(s"illegal LAUNCH cmd: $input")
    }
  }
}