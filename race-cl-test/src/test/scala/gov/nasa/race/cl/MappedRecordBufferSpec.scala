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
package gov.nasa.race.cl

import gov.nasa.race._
import gov.nasa.race.common.CloseStack
import gov.nasa.race.test.RaceSpec
import org.scalatest.FlatSpec

object MappedRecordBufferSpec {

  class ExtrapolatorRecord (nRecords: Int) extends MappedRecord(32,nRecords) {

    // kernel input
    def s_= (v: Double)   = putDouble(0,v)
    def m_= (v: Double)   = putDouble(8,v)
    def tlast_= (v: Long) = putLong(16,v)

    //kernel output
    def estimate          = getDouble(24)
  }

  class ExtrapolatorHostData {

  }
}

/**
  * unit tests for MappedRecordBuffer
  */
class MappedRecordBufferSpec extends FlatSpec with RaceSpec {
  import MappedRecordBufferSpec._

  "a MappedRecordBuffer" should "be directly accessible from both host and device" in {
    val src =
      """
         __kernel void extrapolate (__global int* a, __global int* b, __global int* c) {
           unsigned int i = get_global_id(0);

         }
      """

    tryWithResource(new CloseStack) { implicit resources =>
      val device = CLPlatform.preferredDevice
      println(s"got $device")

      val context = CLContext(device)
      val queue = device.createCommandQueue(context)

      val rec = new ExtrapolatorRecord(10)
      val data = context.createMappedRecordBuffer(rec)

      val prog = context.createProgram(src)
      device.buildProgram(prog)

      val kernel = prog.createKernel("extrapolate")
      kernel.setArgs(data)

      queue.enqueue1DRange(kernel, rec.nRecords)
    }
  }
}