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

package gov.nasa.race.common

import java.io.OutputStream

/**
  * an OutputStream that duplicates to several underlying OutputStreams
  * this is normally used to copy console streams to log files
  */
class TeeOutputStream (val outs: OutputStream*) extends OutputStream {
  override def flush: Unit = outs.foreach(_.flush)
  override def close: Unit = {
    outs.foreach { s =>
      if ((s ne Console.out) && (s ne Console.err)) s.close  // don't close the console output/error streams
    }
  }
  override def write(b: Int): Unit = outs.foreach(_.write(b))
  override def write(bs: Array[Byte]): Unit = outs.foreach(_.write(bs))
  override def write(bs: Array[Byte],off: Int, len: Int): Unit = outs.foreach(_.write(bs,off,len))
}

/**
  * the big output nirvana
  */
class NullOutputStream extends OutputStream {
  override def flush: Unit = {}
  override def close: Unit = {}
  override def write(b: Int): Unit = {}
  override def write(bs: Array[Byte]): Unit = {}
  override def write(bs: Array[Byte],off: Int, len: Int): Unit = {}
}