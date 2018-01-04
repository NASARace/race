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

/**
  * unit tests for basic gov.nasa.race.cl constructs
  */
class CLxSpec extends FlatSpec with RaceSpec {

  "a GPU CLDevice" should "execute a 1D kernel to add array elements" in {

    val src =
      """
         __kernel void add (__global int* a, __global int* b, __global int* c) {
           unsigned int i = get_global_id(0);
           c[i] = a[i] + b[i];
         }
      """

    tryWithResource(new CloseStack) { implicit resources =>
      val device = CLPlatform.preferredDevice
      println(s"got $device")

      val context = CLContext(device)
      val queue = device.createCommandQueue(context)

      val aBuf = context.createIntArrayCRBuffer(Array[Int](1, 2, 3, 4, 5))
      val bBuf = context.createIntArrayCRBuffer(Array[Int](5, 4, 3, 2, 1))
      val cBuf = context.createIntArrayCWBuffer(aBuf.length)

      val prog = context.createProgram(src)
      device.buildProgram(prog)

      val kernel = prog.createKernel("add")
      kernel.setArgs(aBuf, bBuf, cBuf)

      queue.enqueue1DRange(kernel, aBuf.length)
      queue.enqueueRead(cBuf)

      println(s"result: ${cBuf.data.mkString(",")}")
    }
  }
}
