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
package gov.nasa.race.http.share

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.PathIdentifier.{Matcher, globMatcher, resolve}
import gov.nasa.race.common.{CharSeqByteSlice, JsonPullParser, MutUtf8Slice, SliceSplitter}

/**
  * our standard node matchers
  *
  * note this only delegates to PathIdentifier.Matchers in case of explicit node id patterns
  * we can't directly use those Matchers since we also need the current Node to match the abstract <..> patterns
  * and we don't want to cache this as global state
  */
object NodeMatcher {

  object allMatcher extends NodeMatcher {
    def pattern = "<all>"
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = true
  }

  object noneMatcher extends NodeMatcher {
    def pattern = "<none>"
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = false

    override def or (m: NodeMatcher): NodeMatcher = m
  }

  object upMatcher extends NodeMatcher {
    def pattern = "<up>"
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = {
      node.upstreamId.isDefined && sourceNodeId == node.upstreamId.get
    }
  }

  object downMatcher extends NodeMatcher {
    def pattern = "<down>"
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = {
      node.nodeList.downstreamNodes.contains(sourceNodeId.toString)
    }
  }

  object selfMatcher extends NodeMatcher {
    def pattern = "<self>"
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = node.id == sourceNodeId
  }

  object ownerMatcher extends NodeMatcher {
    def pattern = "<owner>"
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = {
      if (targetColumnId == "<self>") sourceNodeId == node.id
      else if (targetColumnId == "<up>" && node.upstreamId.isDefined) sourceNodeId == node.upstreamId.get
      else targetColumnId == sourceNodeId
    }
  }

  //... perhaps more in the future

  //--- those we have to create per-use

  case class PatternMatcher (matcher: Matcher) extends NodeMatcher {
    def pattern = matcher.pattern
    def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean = matcher.matches(sourceNodeId)
  }

  case class OrMatcher(pattern: String, head: NodeMatcher, tail: NodeMatcher) extends NodeMatcher {
    override def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean =  {
      head.matches(sourceNodeId, targetColumnId, node) || tail.matches(sourceNodeId, targetColumnId, node)
    }
  }

  val NONE = asc("<none>")
  val UP = asc("<up>")
  val DOWN = asc("<down>")
  val SELF = asc("<self>")
  val DOT = asc(".")
  val OWNER = asc("<owner>")
}

import gov.nasa.race.http.share.NodeMatcher._

/**
  * filter for send/receive operations of ColumnData (for both Column and Row objects)
  */
trait NodeMatcher {
  def pattern: String

  /**
    * @param sourceNodeId - node id from where the change request originated
    * @param targetColumnId - the target column owner for the change request
    * @param node - the match context (node/column/rowLists)
    * @return
    */
  def matches(sourceNodeId: CharSequence, targetColumnId: CharSequence, node: Node): Boolean

  def or (m: NodeMatcher): NodeMatcher = OrMatcher(s"$pattern,${m.pattern}", this, m)
}


/**
  * JsonPullParser mix-in to parse receive matcher specifications
  */
trait NodeMatcherParser extends JsonPullParser {
  val splitter = new SliceSplitter(',')
  val elem = MutUtf8Slice.empty

  def readNodeMatcher(spec: CharSeqByteSlice, resolverId: String): NodeMatcher = {
    var matcher: NodeMatcher = noneMatcher

    splitter.setSource(spec).foreachMatch(elem) {
      case NONE => // the default
      case UP => matcher = matcher.or(upMatcher)
      case DOWN => matcher = matcher.or(downMatcher)
      case SELF | DOT => matcher = matcher.or(selfMatcher)
      case OWNER => matcher = matcher.or(ownerMatcher)
      case s =>
        if (s.nonEmpty) {
          matcher = matcher.or( PatternMatcher(globMatcher(resolve(s,resolverId))))
        } else {
          throw new RuntimeException(s"unsupported receive filter option: $s")
        }
    }

    matcher
  }
}