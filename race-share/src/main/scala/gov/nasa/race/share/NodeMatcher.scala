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
package gov.nasa.race.share

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

  //--- singleton matchers

  object allMatcher extends NodeMatcher {
    def pattern = "<all>"
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean = true
  }

  object noneMatcher extends NodeMatcher {
    def pattern = "<none>"
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean = false

    override def or (m: NodeMatcher): NodeMatcher = m
  }

  object upMatcher extends NodeMatcher {
    def pattern = "<up>"
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean = {
      node.upstreamId.isDefined && testNodeId == node.upstreamId.get
    }
  }

  object downMatcher extends NodeMatcher {
    def pattern = "<down>"
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean = {
      node.nodeList.downstreamNodes.contains(testNodeId.toString)
    }
  }

  object selfMatcher extends NodeMatcher {
    def pattern = "<self>"
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean = node.id == testNodeId
  }

  object ownerMatcher extends NodeMatcher {
    def pattern = "<owner>"
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]): Boolean = {
      ownerNodeId match {
        case Some(id) =>
          if (id == "<self>" || id == ".") testNodeId == node.id
          else if (id == "<up>" && node.upstreamId.isDefined) testNodeId == node.upstreamId.get
          else id == testNodeId
        case None => false
      }
    }
  }

  //... perhaps more in the future

  //--- instance matchers

  case class PatternMatcher (matcher: Matcher) extends NodeMatcher {
    def pattern = matcher.pattern
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean  = matcher.matches(testNodeId)
  }

  case class OrMatcher(pattern: String, head: NodeMatcher, tail: NodeMatcher) extends NodeMatcher {
    def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean =  {
      head.matches( testNodeId) || tail.matches( testNodeId)
    }
  }

  //--- the matcher spec keywords
  val NONE = asc("<none>")
  val UP = asc("<up>")
  val DOWN = asc("<down>")
  val SELF = asc("<self>")
  val DOT = asc(".")
  val OWNER = asc("<owner>")
}

import gov.nasa.race.share.NodeMatcher._

/**
  * filter for send/receive operations of ColumnData (for both Column and Row objects)
  */
trait NodeMatcher {
  def pattern: String

  override def toString(): String = pattern

  /**
    * matcher for node ids that supports the following specifications:
    *   - lexical id pattern (glob)
    *   - <owner> testNodeId matches the column owner node id
    *   - <self> testNodeId matches context node id
    *   - <up> testNodeId matches the current context node upstreamId
    *   - <down> testNodeId is in context node downstream ids
    *
    * @param testNodeId - node id from where the change request originated
    * @param node - the match context Node
    * @param ownerNodeId - optional owner node id, if undefined matching against <owner> spec will fail
    * @return - true if testNodeId matches the NodeMatcher specification
    */
  def matches (testNodeId: CharSequence) (implicit node: Node, ownerNodeId: Option[String]=None): Boolean

  def matchesInColumn (testNodeId: CharSequence, columnId: String) (implicit node: Node): Boolean = {
    matches(testNodeId)(node, node.columnOwner(columnId))
  }

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