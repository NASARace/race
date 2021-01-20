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
package gov.nasa.race.http.tabdata

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonPullParser, JsonSerializable, JsonWriter}
import gov.nasa.race.uom.DateTime

object Ping {
  val _ping_ = asc("ping")
  val _pong_ = asc("pong")
  val _sender_ = asc("sender")
  val _receiver_ = asc("receiver")
  val _request_ = asc("request")
  val _date_ = asc("date")
}
import Ping._

/**
  * QoS check for connection and end point actors
  *
  * note that we keep track of time and serial number, to detect out-of-order or missed responses
  * note also that we record requester and target so that end points can detect invalid requests and handle DoS attacks
  */
case class Ping (sender: String, receiver: String, request: Int, date: DateTime) extends JsonSerializable {

  def serializeEmbedded (w: JsonWriter): Unit = {
    w.writeMemberObject(_ping_) { _
      .writeStringMember(_sender_,sender.toString)
      .writeStringMember(_receiver_,receiver.toString)
      .writeIntMember(_request_,request)
      .writeDateTimeMember(_date_, date)
    }
  }

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject( w=>serializeEmbedded(w) )
  }
}

trait PingParser extends JsonPullParser {
  def parsePingBody: Option[Ping] = {
    tryParse( x=> warning(s"malformed ping request: ${x.getMessage}")) {
      readCurrentObject { readPingBody }
    }
  }

  def readPingBody: Ping = {
    val sender = readQuotedMember(_sender_).intern
    val receiver = readQuotedMember(_receiver_).intern
    val request = readUnQuotedMember(_request_).toInt
    val date = readDateTimeMember(_date_)
    Ping(sender, receiver, request, date)
  }
}

/**
  * response to QoS check
  *
  * note we copy the incoming request so that the requester can compute roundtrip times without caching requests
  * (if it trusts the sender)
  */
case class Pong(date: DateTime, ping: Ping) extends JsonSerializable {

  def serializeTo (w: JsonWriter): Unit = {
    w.clear().writeObject { _
      .writeMemberObject(_pong_){ w=>
        w.writeDateTimeMember(_date_, date)
        ping.serializeEmbedded(w)
      }
    }
  }
}

trait PongParser extends JsonPullParser with PingParser {
  def parsePongBody: Option[Pong] = {
    tryParse(x=> warning(s"malformed ping response: ${x.getMessage}")) {
      readCurrentObject {
        val date = readDateTimeMember(_date_)
        val ping = readNextObjectMember(_ping_) { readPingBody }
        Pong(date, ping)
      }
    }
  }
}