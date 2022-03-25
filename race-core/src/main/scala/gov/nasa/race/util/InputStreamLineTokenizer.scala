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

package gov.nasa.race.util

import java.io.InputStream

/**
  * a helper trait to tokenize a text line as a list of String objects
  * separated by commas
  *
  * TODO - this is inefficient, move this to slices and re-use the token array
  */
trait InputStreamLineTokenizer {
  def getLineFields(stream: InputStream) : List[String] = {
    val sb = new StringBuilder(64)
    var list = List[String]()
    var c = stream.read
    while (c != -1){
      if (c == '\n'){
        list = sb.toString :: list
        return list.reverse
      } else if (c == ',') {
        list = sb.toString :: list
        sb.clear()
      } else {
        sb += c.toChar
      }
      c = stream.read
    }
    if (sb.size > 0){
      list = sb.toString :: list
    }
    list.reverse
  }
}