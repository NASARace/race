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
import gov.nasa.race.common.{JsonPullParser, JsonSerializable, JsonWriter, PathIdentifier}
import gov.nasa.race.ifSome

object UpdateFilter {
  val _send_ = asc("send")
  val _receive_ = asc("receive")
  val _up_ = asc("<up>")
  val _down_ = asc("<down>")
  val _all_ = asc("<all>")
  val _none_ = asc("<none>")
  val _self_ = asc("<self>")      // id of node this runs on
  val _target_ = asc("<target>")  // id of change item

  // default is we send to upstream and we receive local or from upstream
  val sendUpReceiveLocalUp = new UpdateFilter("<up>", "<self>,<up>")

  val sendReceiveAll = new UpdateFilter("<all>","<all>")
  val localOnly = new UpdateFilter("<none>", "<self>")
}

import UpdateFilter._

/**
  * objects to filter how rows (cell values) are send and received upstream and downstream
  */
class UpdateFilter(val sendSpec: String, val receiveSpec: String, resolverId: String) extends JsonSerializable  {
  import PathIdentifier._

  def this (absSendSpec: String, absReceiveSpec: String) = this (absSendSpec, absReceiveSpec, null)

  protected var sendUp = false
  protected var sendDown = false
  protected var sendMatcher: Matcher = noneMatcher

  protected var receiveUp = false
  protected var receiveDown = false
  protected var receiveSelf = false
  protected var receiveTarget = false
  protected var receiveMatcher: Matcher = noneMatcher

  parseSendSpec(sendSpec)
  parseReceiveSpec(receiveSpec)

  override def toString: String = s"{send: {src: '$sendSpec', up: $sendUp}, receive: {src: '$receiveSpec', up: $receiveUp, down: $receiveDown, self: $receiveSelf, target: $receiveTarget}}"

  def serializeTo (w: JsonWriter): Unit = {
    w.writeStringMember(_send_, sendSpec)
    w.writeStringMember(_receive_, receiveSpec)
  }

  protected def parseSendSpec (spec: String): Unit = {
    sendUp = false; sendDown = false
    sendMatcher = noneMatcher

    spec.split(",").foreach {
      case "<up>" => sendUp = true
      case "<down>" => sendDown = true
      case "<all>" => sendUp = true; sendDown = true
      case "<none>" => // the default
      case s =>
        if (s.nonEmpty && s.charAt(0) != '<') {
          sendMatcher = globMatcher(resolve(s, resolverId))
        } else {
          throw new RuntimeException(s"unsupported send filter option: $s")
        }
    }
  }

  protected def parseReceiveSpec (spec: String): Unit = {
    receiveUp = false; receiveDown = false; receiveSelf = false; receiveTarget = false
    receiveMatcher = noneMatcher

    spec.split(",").foreach {
      case "<up>" => receiveUp = true
      case "<down>" => receiveDown = true
      case "<all>" => receiveUp = true; receiveDown = true; receiveSelf = true; receiveTarget = true
      case "<none>" => // the default
      case "<self>" => receiveSelf = true
      case "<target>" => receiveTarget = true
      case s =>
        if (s.nonEmpty && s.charAt(0) != '<') {
          receiveMatcher = globMatcher(resolve(s,resolverId))
        } else {
          throw new RuntimeException(s"unsupported receive filter option: $s")
        }
    }
  }

  def sendToUpStream (upstreamId: String): Boolean = {
    sendUp | sendMatcher.matches(upstreamId)
  }

  def sendToDownStream (downStreamId: String): Boolean = {
    sendDown | sendMatcher.matches(downStreamId)
  }

  def receiveFromUpStream (upstreamId: String): Boolean = {
    receiveUp | receiveMatcher.matches(upstreamId)
  }

  def receiveFromDownStream (downStreamId: String, targetId: String): Boolean = {
    receiveDown |
      (receiveTarget && downStreamId == targetId) |
      receiveMatcher.matches(downStreamId)
  }

  def receiveFromDevice (nodeId: String, targetId: String): Boolean = {
    receiveSelf && nodeId == targetId
  }
}

trait UpdateFilterParser extends JsonPullParser {

  def parseUpdateFilter(resolveId: String, defFilter: UpdateFilter = sendUpReceiveLocalUp): UpdateFilter = {
    var sendSpec = defFilter.sendSpec
    var receiveSpec = defFilter.receiveSpec

    ifSome(readOptionalQuotedMember(_send_)) { s =>
      sendSpec = s.toString
    }

    ifSome(readOptionalQuotedMember(_receive_)) { s =>
      receiveSpec = s.toString
    }

    if ((sendSpec eq defFilter.sendSpec) && (receiveSpec eq defFilter.receiveSpec)) {
      defFilter // nothing specified
    } else {
      new UpdateFilter(sendSpec,receiveSpec,resolveId)
    }
  }
}