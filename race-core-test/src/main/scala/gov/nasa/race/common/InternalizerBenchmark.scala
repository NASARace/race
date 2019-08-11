/*
 * Copyright (c) 2019, United States Government, as represented by the
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

/**
  * benchmarks for Internalizers compared to standard Java String.intern
  */
object InternalizerBenchmark {

  val ascii8Strings = Array[String](
    "AA001", "AA002", "AA1001", "AA1111", "AA1",
    "SWA8762", "SWA8912", "SWA129", "SWA9999",
    "DAL7", "DAL70", "DAL700", "DAL7000"
  )

  val ascii8Bytes: Array[Array[Byte]] = ascii8Strings.map(_.getBytes)
  val ascii8Chars: Array[Array[Char]] = ascii8Strings.map(_.toCharArray)
  val nStrings = ascii8Strings.length

  def runJavaBytes (nRounds: Int): Long = {
    var i = 0
    val t0 = System.nanoTime
    while (i < nRounds) {
      var j = 0
      while (j < nStrings){
        val bs = ascii8Bytes(j)
        val s = new String(bs, 0, bs.length)
        val is = s.intern
        j += 1
      }
      i += 1
    }
    System.nanoTime - t0
  }

  def runJavaChars (nRounds: Int): Long = {
    var i = 0
    val t0 = System.nanoTime
    while (i < nRounds) {
      var j = 0
      while (j < nStrings){
        val cs = ascii8Chars(j)
        val s = new String(cs, 0, cs.length)
        val is = s.intern
        j += 1
      }
      i += 1
    }
    System.nanoTime - t0
  }

  def runASCII8InternalizerBytes (nRounds: Int): Long = {
    var i = 0
    val t0 = System.nanoTime
    while (i < nRounds) {
      var j = 0
      while (j < nStrings){
        val bs = ascii8Bytes(j)
        val is = ASCII8Internalizer.get(bs,0,bs.length)
        j += 1
      }
      i += 1
    }
    System.nanoTime - t0
  }

  def runASCII8InternalizerChars (nRounds: Int): Long = {
    var i = 0
    val t0 = System.nanoTime
    while (i < nRounds) {
      var j = 0
      while (j < nStrings){
        val cs = ascii8Chars(j)
        val is = ASCII8Internalizer.get(cs,0,cs.length)
        j += 1
      }
      i += 1
    }
    System.nanoTime - t0
  }

  def main (args: Array[String]): Unit = {

    val nRounds = 1000000
    val rt = Runtime.getRuntime

    //--- warm up
    runJavaBytes(nRounds)
    runJavaChars(nRounds)
    runASCII8InternalizerBytes(nRounds)
    runASCII8InternalizerChars(nRounds)

    println(s"--- $nRounds:")
    rt.gc
    println(f"  Java bytes: ${runJavaBytes(nRounds)}%20d ns")
    rt.gc
    println(f"  Java chars: ${runJavaChars(nRounds)}%20d ns")
    rt.gc
    println(f"  A8I bytes:  ${runASCII8InternalizerBytes(nRounds)}%20d ns")
    rt.gc
    println(f"  A8I chars:  ${runASCII8InternalizerChars(nRounds)}%20d ns")
  }
}
