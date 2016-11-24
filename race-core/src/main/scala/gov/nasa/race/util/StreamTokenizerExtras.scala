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

import java.io.StreamTokenizer

/**
 * trait with extra methods for StreamTokenizer instances, mostly for initialization convenience
 */
trait StreamTokenizerExtras {
  this: StreamTokenizer =>

  def wordChar (char: Int) = wordChars(char,char)
  def whitespaceChar (char: Int) = whitespaceChars(char,char)

  def setCategory(cat: (Int,Int)=>Unit, chars: Int*) = for (c <- chars) cat(c,c)
  def setCategoryRange(cat: (Int,Int)=>Unit, charFrom: Int, charTo: Int) = cat(charFrom, charTo)
}
