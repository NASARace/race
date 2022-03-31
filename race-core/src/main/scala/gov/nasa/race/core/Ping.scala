/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.core

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonPullParser, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime

object Ping {
  val PING = asc("ping")
  val PONG = asc("pong")
  val SENDER = asc("sender")
  val RECEIVER = asc("receiver")
  val REQUEST = asc("request")
  val DATE = asc("date")

  val PingRE = """\s*\{\s*"ping":\s*\{\s*"sender":\s*"(.*)",\s*"receiver":\s*"(.*)",\s*"request":\s*(\d+),\s*"date":\s*(\d+)\s*\}\s*\}""".r

  // convenience method in case we don't already have a PingParser
  def ifPing(s: String)(action: Ping=>Unit): Unit = {
    s match {
      case PingRE(pinger,ponger,request,pingDtg) =>
        val ping = Ping(pinger,ponger,request.toInt,DateTime.ofEpochMillis(pingDtg.toLong))
        action(ping)
    }
  }
}
import gov.nasa.race.core.Ping._

/**
  * QoS check for connection and end point actors
  *
  * note that we keep track of time and serial number, to detect out-of-order or missed responses
  * note also that we record requester and target so that end points can detect invalid requests and handle DoS attacks
  */
case class Ping (sender: String, receiver: String, request: Int, date: DateTime) extends JsonSerializable {

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeObjectMember(PING) { w=>
      w.writeStringMember(SENDER,sender.toString)
      w.writeStringMember(RECEIVER,receiver.toString)
      w.writeIntMember(REQUEST,request)
      w.writeDateTimeMember(DATE, date)
    }
  }
}

trait PingParser extends JsonPullParser {
  def readPing(): Ping = {
    readCurrentObject {
      val sender = readQuotedMember(SENDER).intern
      val receiver = readQuotedMember(RECEIVER).intern
      val request = readUnQuotedMember(REQUEST).toInt
      val date = readDateTimeMember(DATE)
      Ping(sender, receiver, request, date)
    }
  }

  def parsePing(): Option[Ping] = {
    tryParse( x=> warning(s"malformed ping request: ${x.getMessage}")) {
      readPing()
    }
  }
}

object Pong {
  val PongRE = """\s*\{\s*"pong":\s*\{\s*"date":\s*(\d+),\s*"ping":\s*\{\s*"sender":\s*"(.*)",\s*"receiver":\s*"(.*)",\s*"request":\s*(\d+),\s*"date":\s*(\d+)\s*\}\s*\}\s*\}""".r

  // convenience method in case we don't have a PongParser at hand
  def ifPong(s: String)(action: Pong=>Unit): Unit = {
    s match {
      case PongRE(pongDtg,pinger,ponger,request,pingDtg) =>
        val ping = Ping(pinger,ponger,request.toInt,DateTime.ofEpochMillis(pingDtg.toLong))
        val pong = Pong(DateTime.ofEpochMillis(pongDtg.toLong),ping)
        action(pong)
    }
  }
}

/**
  * response to QoS check
  *
  * note we copy the incoming request so that the requester can compute roundtrip times without caching requests
  * (if it trusts the sender)
  */
case class Pong(date: DateTime, ping: Ping) extends JsonSerializable {

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeObjectMember(PONG){ w=>
      w.writeDateTimeMember(DATE, date)
      ping.serializeMembersTo(w)
    }
  }
}

trait PongParser extends JsonPullParser with PingParser {

  // this assumes the '{"pong:"' prefix has already been parsed
  def parsePong(): Option[Pong] = {
    tryParse(x=> warning(s"malformed ping response: ${x.getMessage}")) {
      readCurrentObject {
        val date = readDateTimeMember(DATE)
        val ping = readNextObjectMember(PING) { readPing() }
        Pong(date, ping)
      }
    }
  }
}