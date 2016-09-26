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

package gov.nasa.race.data

/**
 * this class keeps track of:
 *   total number of messages
 *   total size in bytes
 *   number of msg per sec
 *   size of msg per sec
 */
class MessageStats(val tag: String, val tagLevel: Int){

  var num = 0; var size = 0;
  var msgRate = 0d; var byteRate = 0d;

  def addNum(i: Int) =
    num += i

  def addSize(s: Int) =
    size += s

  def apply (num: Int, size: Int, elapsed: Int) = {
    addNum(num)
    addSize(size)
    computeRates(elapsed)
  }

  // <2do> pcm - this should use a FiniteDuration to compute proper msg/sec
  def computeRates(duration: Int) = {
    if(duration>0) {
      msgRate = (num.toDouble / duration.toDouble)
      byteRate = (size.toDouble / duration.toDouble)
    }
  }

  def formatted = f"  $tag%-35s  $tagLevel%9d $num%11d  $size%10d ${msgRate}%13.2f  ${byteRate}%13.2f\n"
}

object MessageStats {
  def header =   """  msg tag                              tag level  num of msg  size(byte)  rate(msg/s)    rate(byte/s)
                   |  -----------------------------------  ---------  ----------  ----------  -------------  -------------""".stripMargin

  def solidLine =  """  ----------------------------------------------------------------------------------------------------""".stripMargin
}