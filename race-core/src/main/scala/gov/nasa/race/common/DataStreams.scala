/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import java.io.{DataInputStream, DataOutputStream}

import gov.nasa.race.SchemaImplementor

/**
  * something that can read schema compliant data from a DataInputStream
  */
trait DataStreamReader extends SchemaImplementor {
  def read (dis: DataInputStream): Option[Any]

  // convenience method to read a string with max 256 chars that is stored as .. byteLen {bytes} ..
  // Note - user has to provide temp buffer for reading the bytes
  protected def readString256 (dis: DataInputStream, buf: Array[Byte]): String = {
    val nBytes = dis.readUnsignedByte
    val nRead = dis.read(buf,0,nBytes)
    new String(buf,0,nRead)
  }

  protected def matchBytes (dis: DataInputStream, data: Array[Byte]): Boolean = {
    var i = 0
    try {
      while (i < data.length) {
        if (dis.readByte != data(i)) return false
        i += 1
      }
      true
    } catch {
      case _: Throwable => false
    }
  }
}

/**
  * something that can write schema compliant data from a DataInputStream
  */
trait DataStreamWriter extends SchemaImplementor {
  def write (dos: DataOutputStream, data: Any): Int

  protected def writeString256 (dos: DataOutputStream, s: String) = {
    val n = Math.max(s.length, 256)
    val b = s.getBytes
    dos.write(b,0,n)
  }
}


class StringDataStreamReader extends DataStreamReader {

  def read (dis: DataInputStream): Option[Any] = {
    val n = dis.available


    val bs = new Array[Byte](n)
    dis.readFully(bs)
    Some(new String(bs))
  }

  override val schema: String = "<utf8>"
}