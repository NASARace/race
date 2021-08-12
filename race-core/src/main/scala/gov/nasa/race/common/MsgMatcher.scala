/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.common

import gov.nasa.race.config.ConfigUtils._
import com.typesafe.config.Config
import gov.nasa.race.util.StringUtils

import scala.util.matching.Regex
import scala.jdk.CollectionConverters._


/**
  * a list of (name, regexes) pairs to classify text messages
  *
  * can be used to categorize text messages according to regular expression matches, and configured like this:
  *  ...
  *  classifiers = [
  *    { name = "invalid position value (no separator space)"
  *      patterns = ["<!-- cvc-.*: The value '[0-9.\\-]+' of element 'pos' is not valid. -->"] }, ...
  */
object MsgMatcher {

  def getMsgMatchers(config: Config): Seq[MsgMatcher] = {
    config.getOptionalConfigList("matchers").reverse.foldLeft(List.empty[MsgMatcher]) { (list, conf) =>
      val name = conf.getString("name")
      val patterns = conf.getStringList("patterns").asScala.map(new Regex(_)).toSeq
      MsgMatcher(name, patterns) :: list
    }
  }

  def findFirstMsgMatcher(msg: CharSequence, matchers: Seq[MsgMatcher]): Option[MsgMatcher] = {
    matchers.find(c=> StringUtils.matchesAll(msg,c.patterns))
  }
}

case class MsgMatcher(name: String, patterns: Seq[Regex]) {
  private val cs = MutUtf8Slice.empty

  def matchCount (msg: CharSequence): Int = patterns.foldLeft(0)((acc,p) => acc + p.findAllIn(msg).size )

  def matchCount (bs: Array[Byte], off: Int, len: Int): Int = {
    cs.set(bs,off,len)
    patterns.foldLeft(0)((acc,p) => {
      cs.reset()
      acc + p.findAllIn(cs).size
    })
  }

}
