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
package gov.nasa.race.util

import java.nio.{Buffer, ByteBuffer, ByteOrder}

import sun.misc.Unsafe

/**
  * interface for sun.misc.Unsafe - USE WITH CAUTION
  */
object UnsafeUtils {

  private val unsafe = {
    // NOTE this only works on Sun/OpenJDK and might require adaptation for Java 9
    val f = classOf[Unsafe].getDeclaredField("theUnsafe")
    f.setAccessible(true)
    f.get(null).asInstanceOf[Unsafe]
  }

  /**
    * CAS support for DirectByteBuffers. The primary use for this class is to enable fine grained (per record)
    * locking for shared memory
    *
    * Note - since DirectByteBuffer is not a public type we have to resort to runtime exceptions in case
    * the provided byte buffer is not of the right type
    *
    * Note - the provided buffer has to be in native byte order. Since it is supposed to be shared with other
    * processes on the same machine everything else would be pointless
    */
  final class DirectByteBufferAccessor (val buffer: ByteBuffer) {
    private val address: Long = {
      val f = classOf[Buffer].getDeclaredField("address")
      f.setAccessible(true)
      f.getLong(buffer)
    }

    private val maxOffset = buffer.capacity-4

    if (!buffer.isDirect || address == 0) throw new IllegalArgumentException(s"not a valid direct/mapped buffer: $buffer")
    if (buffer.order != ByteOrder.nativeOrder) throw new RuntimeException(s"buffer not in native ByteOrder: $buffer")


    def compareAndSwapInt (offset: Long, expect: Int, v: Int): Boolean = {
      if (offset < 0 || offset > maxOffset) throw new IndexOutOfBoundsException(s"offset $offset out of bounds")
      unsafe.compareAndSwapInt(null,address + offset,expect,v)
    }

    def getIntVolatile (offset: Long): Int = {
      unsafe.getIntVolatile(null,address + offset)
    }
    def putIntVolatile (offset: Long, v: Int): Unit = {
      if (offset < 0 || offset > maxOffset) throw new IndexOutOfBoundsException(s"offset $offset out of bounds")
      unsafe.putIntVolatile(null,address + offset,v)
    }

    def getAndAddInt(offset: Long, delta: Int): Int = {
      if (offset < 0 || offset > maxOffset) throw new IndexOutOfBoundsException(s"offset $offset out of bounds")
      unsafe.getAndAddInt(null,address + offset,delta)
    }
  }

  def getAccessor(buffer: ByteBuffer) = new DirectByteBufferAccessor(buffer)
}
