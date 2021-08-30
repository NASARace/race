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

import java.nio.ByteBuffer
import gov.nasa.race._
import gov.nasa.race.common.{BufferRecord, CloseStack}
import gov.nasa.race.test.RaceSpec
import org.scalatest.Ignore
import org.scalatest.flatspec.AnyFlatSpec

class TestRecord (buffer: ByteBuffer) extends BufferRecord(20,buffer) {
  var a = double(0)
  var b = int(8)
  var c = double(12)

  // just some syntactic sugar
  def set (_a: Double, _b: Int, _c: Double): Unit = { a := _a; b := _b; c := _c }
}

/**
  * unit tests for MappedRecordBuffer
  */
@Ignore  // unfortunately, as of macOS 10.15.7 OpenCL causes spurious errors in the driver
class CLMappedRecordBufferSpec extends AnyFlatSpec with RaceSpec {

  "a MappedRecordBuffer" should "be directly accessible from both host and device" in {

      val src = """
         typedef struct __attribute__ ((packed)) _test_record {
           double a;
           int b;
           double c;
         } test_record_t;

         __kernel void compute_c (__global test_record_t* rec) {
           unsigned int i = get_global_id(0);

           rec[i].c = rec[i].a + rec[i].b;
           //printf("### [%d]: %f + %d = %f\n", i, rec[i].a, rec[i].b, rec[i].c);
         }
      """

    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice
      println(s"got $device")

      val context = device.createContext                                     >> resources
      val queue = device.createCommandQueue(context)                         >> resources

      val data = context.createMappedRecordBuffer(2, new TestRecord(_))      >> resources

      queue.executeMapped(data) { buf =>
        val rec = new TestRecord(buf)
        rec(0).set( 41.0, 1, 0.0 )
        rec(1).set( 40.0, 2, 0.0 )
      }

      val prog = context.createAndBuildProgram(src)                          >> resources
      val kernel = prog.createKernel("compute_c")                            >> resources
      kernel.setArgs(data)

      queue.enqueue1DRange(kernel, data.length)
      queue.finish

      val res = queue.executeMappedWithRecord(data) { rec =>
        var sum = 0.0
        rec.foreach {
          val a: Double = rec.a
          val b: Int = rec.b
          val c: Double = rec.c
          println(s"[${rec.index}] $a + $b = $c")
          sum += c
        }
        sum
      }
      println(s"sum is: $res")
    }
  }
}