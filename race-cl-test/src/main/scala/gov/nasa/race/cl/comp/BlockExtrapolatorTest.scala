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
package gov.nasa.race.cl.comp

import gov.nasa.race.common._
import gov.nasa.race.cl.CLPlatform
import gov.nasa.race.common.CloseStack
import gov.nasa.race.tryWithResource

import scala.concurrent.duration._

/**
  * test application to benchmark BlockExtrapolator vs. SmoothingVectorExtrapolators
  */
object BlockExtrapolatorTest {

  val E = 1000 // number of objects
  val S = 8    // number of states per object (phi,lambda,alt,v,hdg,pitch,roll,vr)
  val N = 10   // number of observations per entity
  val R = 1000  // rounds of extrapolation

  def main (args: Array[String]): Unit = {
    System.gc
    runBE
    System.gc
    runSE
  }

  val src =
    """
      typedef struct __attribute__ ((packed)) _e_in {
        long t_last;
        double8 s;
        double8 m;
      } e_in_t;

      typedef struct __attribute__ ((packed)) _e_out {
        double8 e;
      } e_out_t;

      __kernel void extrapolate (__global e_in_t* in, __global e_out_t* out, long t, double t_scale) {
           unsigned int i = get_global_id(0);
           long dt = t - in[i].t_last;
           out[i].e = in[i].s + (dt * in[i].m) / t_scale;
      }
    """

  def createAndInitBE: BlockExtrapolator = {
    val be = new BlockExtrapolator(E, 1.millisecond, // max N entries with 8 state vars each
      Array[Double](0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
      Array[Double](0.3, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3, 0.3),
      Array[Double](0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9)
    )

    //--- init observation data (values don't matter)
    val t = new Array[Long](N)
    val v = new Array[Double](N)
    for (i <- 0 until N) {
      t(i) = i
      v(i) = i
    }

    val id = new Array[String](E)
    for (i <- 0 until E) id(i) = i.toString

    //--- train extrapolator (entities don't need different values)
    val s = new Array[Double](S)
    for (i <- 0 until N) {
      for (k <- 0 until S) s(k) = v(i)
      for (j <- 0 until E) {
        be.addObservation(id(j), s, t(i))
      }
    }

    be
  }

  def runBE: Unit = {
    val be = createAndInitBE
    val t: Long = 15

    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice                             >> resources
      println(s"got $device")

      val context = device.createContext                                  >> resources
      val queue = device.createJobCommandQueue(context)                   >> resources

      val inBuf = context.createByteRBuffer(be.outBuffer)                 >> resources
      val outBuf = context.createByteWBuffer(be.inBuffer)                 >> resources

      val prog = context.createAndBuildProgram(src)                       >> resources
      val kernel = prog.createKernel("extrapolate")                >> resources

      kernel.setArgs(inBuf,outBuf,t,be.tScale)
      queue.enqueueBufferWrite(inBuf)

      /**/
      val nanos = measureNanos(R) {
        // here we would set up inBuf
        //inBuf.buf.rewind

        //kernel.setArgs(inBuf,outBuf,t,be.tScale)

        queue.enqueueBufferWrite(inBuf)
        queue.enqueue1DRange(kernel, be.workItems)
        queue.enqueueBufferRead(outBuf, true)
      }

      println(s"BE $R rounds: ${nanos/1000000}msec (${be.workItems} work items)")
        /**/
    }
  }

  /**
    * base line: iterating over per-entry SmoothingVectorExtrapolators
    */
  def runSE: Unit = {
    val se = new Array[SmoothingVectorExtrapolator](E)
    for (i <- 0 until E) se(i) = new SmoothingVectorExtrapolator(S,1.millisecond, 0.3, 0.9)

    val t = new Array[Long](N)
    val v = new Array[Double](N)
    for (i <- 0 until N) {
      t(i) = i
      v(i) = i
    }

    val s = new Array[Double](S)
    for (i <- 0 until N) {
      for (k <- 0 until S) s(k) = v(i)
      for (j <- 0 until E) {
        se(j).addObservation(s, t(i))
      }
    }

    val tEx: Long = 15
    val ve = new Array[Double](S)

    val nanos = measureNanos(R) {
      for (i <- 0 until E) {
        se(i).extrapolate(tEx,ve)
      }
    }

    println(s"SE $R rounds: ${nanos/1000000}msec")
  }
}
