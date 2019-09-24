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

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CoderResult, StandardCharsets}


/**
  * utility class that basically does a String.getBytes() into a re-usable buffer so that we
  * can save per-call allocation
  */
class UTF8Buffer (initBufSize: Int = 8192) {
  var data: Array[Byte] = new Array(initBufSize)
  var length: Int = 0

  protected var bb: ByteBuffer = ByteBuffer.wrap(data)
  protected val enc = StandardCharsets.UTF_8.newEncoder

  protected def growBuf: Unit = {
    val newBuf = new Array[Byte](data.length*2)
    val newBB = ByteBuffer.wrap(newBuf)
    newBB.put(bb)
    bb = newBB

    data = newBuf
  }

  def encode (s: String): Int = {
    val cb = CharBuffer.wrap(s)
    bb.rewind
    length = 0
    var done = false

    do {
      enc.encode(cb, bb, true) match {
        case CoderResult.UNDERFLOW => // all chars encoded
          length = bb.position
          done = true
        case CoderResult.OVERFLOW => growBuf
      }
    } while (!done)

    length
  }
}