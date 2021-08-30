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
import org.scalatest.Ignore
import org.scalatest.flatspec.AnyFlatSpec

/**
  * unit tests for basic gov.nasa.race.cl constructs
  */
@Ignore  // unfortunately, as of macOS 10.15.7 OpenCL causes spurious errors in the driver
class CLxSpec extends AnyFlatSpec with RaceSpec {

  val src =
    """
         __kernel void add (__global int* a, __global int* b, __global int* c) {
           unsigned int i = get_global_id(0);
           //printf("%d: %d + %d\n", i, a[i],b[i]);
           c[i] = a[i] + b[i];
         }
      """

  "a GPU CLDevice" should "execute a 1D kernel to add ArrayBuffer elements" in {
    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice                                >> resources
      println(s"got $device")

      val context = device.createContext                                     >> resources
      val queue = device.createJobCommandQueue(context)                      >> resources

      val aBuf = context.createArrayRBuffer(Array[Int](38, 39, 40, 41, 42))  >> resources
      val bBuf = context.createArrayRBuffer(Array[Int](4, 3, 2, 1, 0))       >> resources
      val cBuf = context.createArrayWBuffer[Int](aBuf.length)                >> resources

      val prog = context.createAndBuildProgram(src)                          >> resources
      val kernel = prog.createKernel("add")                           >> resources
      kernel.setArgs(aBuf, bBuf, cBuf)

      val job = queue.submitJob {
        queue.enqueue1DRange(kernel, aBuf.length)
        queue.enqueueBlockingArrayBufferRead(cBuf)
      }

      //queue.waitForJob(job) {
      // nothing to wait for since cBuf read was blocking
        println(s"result: ${cBuf.data.mkString(",")}")
      //}
    }
  }

  "a GPU CLDevice" should "execute a 1D kernel to add (non-blocking) IntBuffer elements" in {
    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice                                >> resources
      println(s"got $device")

      val context = device.createContext                                     >> resources
      val queue = device.createJobCommandQueue(context)                      >> resources

      val aInit = Array[Int](38, 39, 40, 41, 42)
      val aBuf = context.createIntRBuffer(aInit.length)                      >> resources
      aBuf.put(aInit)

      val bInit = Array[Int](4, 3, 2, 1, 0)
      val bBuf = context.createIntRBuffer(bInit.length)                      >> resources
      bBuf.put(bInit)

      val cBuf = context.createIntWBuffer(aBuf.length)                       >> resources
      cBuf.clear

      val prog = context.createAndBuildProgram(src)                          >> resources
      val kernel = prog.createKernel("add")                           >> resources
      kernel.setArgs(aBuf, bBuf, cBuf)

      val job = queue.submitJob {
        queue.enqueueBufferWrite(aBuf)
        queue.enqueueBufferWrite(bBuf)
        queue.enqueue1DRange(kernel, aBuf.length)
        queue.enqueueBufferRead(cBuf, false)  // this is non-blocking
      }

      queue.waitForJob(job) {
        print("result: ")
        var i = 0
        val max = cBuf.length
        while (i < max) {
          print(s"${cBuf(i)},")
          i += 1
        }
        println()
      }
    }
  }
}
