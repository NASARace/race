/*
 * Copyright (c) 2022, United States Government, as represented by the
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

import gov.nasa.race.{SchemaImplementor, trySome}

import java.nio.ByteBuffer

/**
  * a reader that parses a ByteBuffer
  * note this buffer might be re-used, i.e. products are not allowed to hold references to it
  */
trait ByteBufferReader extends SchemaImplementor {
  def read (bb: ByteBuffer): Option[Any]

  @inline def remainingBytes(bb:ByteBuffer): Array[Byte] = {
    val bs = new Array[Byte](bb.remaining)
    bb.get(bs)
    bs
  }
}


class RawBBReader extends ByteBufferReader {
  override val schema: String = "ByteArray"

  override def read(bb: ByteBuffer): Option[Any] = {
    Some(remainingBytes(bb))
  }
}

class StringBBReader extends ByteBufferReader {
  override val schema: String = "String"

  override def read(bb: ByteBuffer): Option[Any] = {
    trySome( new String(bb.array, bb.position, bb.remaining))
  }
}