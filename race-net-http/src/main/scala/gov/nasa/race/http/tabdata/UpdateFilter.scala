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

import java.nio.file.{Path, PathMatcher}

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{AllMatcher, JsonPullParser, JsonSerializable, JsonWriter, NoneMatcher, UnixPath}
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

class UpdateFilter(val sendSpec: String, val receiveSpec: String, resolver: Option[Path]=None) extends JsonSerializable  {

  protected var sendUp = false
  protected var sendDown = false
  protected var sendMatcher: PathMatcher = NoneMatcher

  protected var receiveUp = false
  protected var receiveDown = false
  protected var receiveSelf = false
  protected var receiveTarget = false
  protected var receiveMatcher: PathMatcher = NoneMatcher

  parseSendSpec(sendSpec)
  parseReceiveSpec(receiveSpec)

  def serializeTo (w: JsonWriter): Unit = {
    w.writeStringMember(_send_, sendSpec)
    w.writeStringMember(_receive_, receiveSpec)
  }

  def resolvePattern (s: String): PathMatcher = {
    resolver match {
      case Some(p) => UnixPath.globMatcher(UnixPath.resolvePattern(p,s))
      case None => UnixPath.globMatcher(s)
    }
  }

  protected def parseSendSpec (spec: String): Unit = {
    sendUp = false; sendDown = false
    sendMatcher = NoneMatcher

    spec.split(",").foreach {
      case "<up>" => sendUp = true
      case "<down>" => sendDown = true
      case "<all>" => sendUp = true; sendDown = true
      case "<none>" => // the default
      case s =>
        if (s.nonEmpty && s.charAt(0) != '<') {
          sendMatcher = resolvePattern(s)
        } else {
          throw new RuntimeException(s"unsupported send filter option: $s")
        }
    }
  }

  protected def parseReceiveSpec (spec: String): Unit = {
    receiveUp = false; receiveDown = false; receiveSelf = false; receiveTarget = false
    receiveMatcher = NoneMatcher

    spec.split(",").foreach {
      case "<up>" => receiveUp = true
      case "<down>" => receiveDown = true
      case "<all>" => receiveUp = true; receiveDown = true
      case "<none>" => // the default
      case "<self>" => receiveSelf = true
      case "<target>" => receiveTarget = true
      case s =>
        if (s.nonEmpty && s.charAt(0) != '<') {
          receiveMatcher = resolvePattern(s)
        } else {
          throw new RuntimeException(s"unsupported receive filter option: $s")
        }
    }
  }

  def sendToUpStream (upstreamId: Path): Boolean = {
    sendUp | sendMatcher.matches(upstreamId)
  }

  def sendToDownStream (downStreamId: Path): Boolean = {
    sendDown | sendMatcher.matches(downStreamId)
  }

  def receiveFromUpStream (upstreamId: Path): Boolean = {
    receiveUp | receiveMatcher.matches(upstreamId)
  }

  def receiveFromDownStream (downStreamId: Path, targetId: Path): Boolean = {
    receiveDown |
      (receiveTarget && downStreamId == targetId) |
      receiveMatcher.matches(downStreamId)
  }

  def receiveFromDevice (nodeId: Path, targetId: Path): Boolean = {
    receiveSelf && nodeId == targetId
  }
}

trait UpdateFilterParser extends JsonPullParser {

  def parseUpdateFilter(resolveId: Option[Path], defFilter: UpdateFilter = sendUpReceiveLocalUp): UpdateFilter = {
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