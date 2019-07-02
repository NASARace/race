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
package gov.nasa.race.cl

import java.nio.ByteBuffer

import gov.nasa.race._
import gov.nasa.race.common._
import gov.nasa.race.common.BufferRecord
import gov.nasa.race.main.CliArgs
import gov.nasa.race.tryWithResource

import org.lwjgl.system.Configuration

/**
  * benchmark to compare OpenCL data management strategies
  */
object Benchmarks {

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}"){
    var listDevices = false
    var platformIndex = 0
    var deviceIndex = 0
    var workitems = 5000
    var rounds = 1000
    var runOnly: Option[String] = None
    var delay = false
    var noCheck = false

    opt1("-p","--platform")("<index>",s"platform index (default $platformIndex)") { a=> platformIndex = a.toInt}
    opt1("-d","--device")("<index>",s"device index (default $deviceIndex)") { a=> deviceIndex = a.toInt}

    opt1("-w","--workitems")("<n>",s"work item size (default $workitems)") { a=> workitems = a.toInt}
    opt1("-r","--rounds")("<n>",s"number of cycles (default $rounds)") {a=> rounds = a.toInt}

    opt1("--run-only")("host|copy|map", "test to run"){ a=> runOnly = Some(a) }
    opt0("--delay")("delay start") { delay = true }
    opt0("--no-check")("disable LWJGL checks and debugging") { noCheck = true }

    opt0("--list")("list platforms/devices") { listDevices = true }

    // and maybe more to follow, such as kernel, workgroup size etc
  }

  var opts: Opts = new Opts

  //--- global CL constructs
  var device: CLDevice = null
  var context: CLSingleDeviceContext = null
  var queue: CLCommandQueue = null

  class TestInRecord (buffer: ByteBuffer) extends BufferRecord(12,buffer) {
    val a = float(0)
    val b = float(4)
    val c = int(8)
  }

  class TestOutRecord (buffer: ByteBuffer) extends BufferRecord(12, buffer) {
    val x = float(0)
    val y = float(4)
    val z = float(8)
  }

  class TestInOutRecord (buffer: ByteBuffer) extends BufferRecord(24,buffer) {
    val a = float(0)
    val b = float(4)
    val c = int(8)

    val x = float(12)
    val y = float(16)
    val z = float(20)
  }

  val twoBufferKernelSrc =
    """
      |  typedef struct __attribute__ ((packed)) _test_in_record {
      |    float a;
      |    float b;
      |    int c;
      |  } test_in_record_t;
      |
      |  typedef struct __attribute__ ((packed)) _test_out_record {
      |    float x;
      |    float y;
      |    float z;
      |  } test_out_record_t;
      |
      |  __kernel void two_buffer_kernel (__global test_in_record_t* in, __global test_out_record_t* out) {
      |     unsigned int i = get_global_id(0);
      |
      |     out[i].x = in[i].a * in[i].c;
      |     out[i].y = in[i].b * in[i].c;
      |     out[i].z = (in[i].a + in[i].b) * in[i].c;
      |
      |     //printf("[%d]: %f %f %d -> %f %f %f\n", i, in[i].a, in[i].b, in[i].c, out[i].x, out[i].y, out[i].z);
      |  }
    """.stripMargin

  val oneBufferKernelSrc =
    """
      |  typedef struct __attribute__ ((packed)) _test_inout_record {
      |    float a;
      |    float b;
      |    int c;
      |
      |    float x;
      |    float y;
      |    float z;
      |  } test_inout_record_t;
      |
      |  __kernel void one_buffer_kernel (__global test_inout_record_t* rec) {
      |     unsigned int i = get_global_id(0);
      |
      |     rec[i].x = rec[i].a * rec[i].c;
      |     rec[i].y = rec[i].b * rec[i].c;
      |     rec[i].z = (rec[i].a + rec[i].b) * rec[i].c;
      |  }
    """.stripMargin

  val arrayKernelSrc =
    """
      |  __kernel void array_kernel (__global float* a, __global float* b, __global int* c,
      |                              __global float* x, __global float* y, __global float* z) {
      |    unsigned int i = get_global_id(0);
      |
      |    x[i] = a[i] * c[i];
      |    y[i] = b[i] * c[i];
      |    z[i] = (a[i] + b[i]) * c[i];
      |  }
    """.stripMargin


  //--------------------------------- explicit objects, CPU only

  class InObj (val a: Float, val b: Float, val c: Int)
  class OutObj (var x: Float, var y: Float, var z: Float)

  def compute (in: InObj, out: OutObj): Unit = {
    out.x = in.a * in.c
    out.y = in.b * in.c
    out.z = (in.a + in.b) * in.c
  }

  // slightly faster but a lot more memory
  def _testHost (showResult: Boolean): Unit = {
    val nRounds = opts.rounds
    val nWorkItems = opts.workitems

    val aIn = new Array[InObj](nWorkItems)
    val aOut = new Array[OutObj](nWorkItems)

    for (i <- 0 until nWorkItems) aIn(i) = new InObj(2,3,2)
    for (i <- 0 until nWorkItems) aOut(i) = new OutObj(0,0,0)

    val nanos = measureNanos(nRounds) {
      for (i <- 0 until nWorkItems) { compute( aIn(i), aOut(i)) }
    }
    //println(s"@@ ${aOut(nWorkItems-1).x}, ${aOut(nWorkItems-1).y}, ${aOut(nWorkItems-1).z}")
    if (showResult) printOutput("testHost",nRounds,nWorkItems,nanos)
  }

  def testHost(showResult: Boolean): Unit = {
    val nRounds = opts.rounds
    val nWorkItems = opts.workitems

    val a = new Array[Float](nWorkItems)
    val b = new Array[Float](nWorkItems)
    val c = new Array[Int](nWorkItems)
    val r1 = new Array[Float](nWorkItems)
    val r2 = new Array[Float](nWorkItems)
    val r3 = new Array[Float](nWorkItems)

    for (i <- 0 until nWorkItems){
      a(i) = 2.0f
      b(i) = 3.0f
      c(i) = 2
    }

    val nanos = measureNanos(nRounds) {
      for (i <- 0 until nWorkItems) {
        r1(i) = a(i) * c(i)
        r2(i) = b(i) * c(i)
        r3(i) = (a(i) + b(i)) * c(i)
      }
    }
    //println(s"@@ ${r1(nWorkItems-1)}, ${r2(nWorkItems-1)}, ${r3(nWorkItems-1)}")
    if (showResult) printOutput("testHost",nRounds,nWorkItems,nanos)
  }

  //-------------------------------------- arrays

  def testArrays (showResult: Boolean): Unit = {
    val nRounds = opts.rounds
    val nWorkItems = opts.workitems

    tryWithResource(new CloseStack) { resources =>
      val a = context.createArrayRBuffer[Float](nWorkItems) >> resources
      val b = context.createArrayRBuffer[Float](nWorkItems) >> resources
      val c = context.createArrayRBuffer[Int](nWorkItems) >> resources

      val x = context.createArrayWBuffer[Float](nWorkItems) >> resources
      val y = context.createArrayWBuffer[Float](nWorkItems) >> resources
      val z = context.createArrayWBuffer[Float](nWorkItems) >> resources

      for (i <- 0 until nWorkItems) {
        a.data(i) = 2
        b.data(i) = 3
        c.data(i) = 2
      }

      val prog = context.createAndBuildProgram(arrayKernelSrc) >> resources
      val kernel = prog.createKernel("array_kernel") >> resources
      kernel.setArgs(a,b,c,x,y,z)

      val nanos = measureNanos(nRounds) {
        queue.enqueueBlockingArrayBufferWrite(a)
        queue.enqueueBlockingArrayBufferWrite(b)
        queue.enqueueBlockingArrayBufferWrite(c)

        queue.enqueue1DRange(kernel, nWorkItems)

        queue.enqueueBlockingArrayBufferRead(x)
        queue.enqueueBlockingArrayBufferRead(y)
        queue.enqueueBlockingArrayBufferRead(z)
      }
      if (showResult) printOutput("testArrays",nRounds,nWorkItems,nanos)
    }
  }

  //-------------------------------------- two buffers, copy

  def testTwoBufferCopy (showResult: Boolean): Unit = {
    val nRounds = opts.rounds
    val nWorkItems = opts.workitems

    tryWithResource(new CloseStack) { resources =>
      val inBuf = context.createRecordRBuffer(nWorkItems, new TestInRecord(_)) >> resources
      val outBuf = context.createRecordWBuffer(nWorkItems, new TestOutRecord(_)) >> resources

      inBuf.foreachRecord{ r =>
        r.a := 2
        r.b := 3
        r.c := 2
      }

      val prog = context.createAndBuildProgram(twoBufferKernelSrc) >> resources
      val kernel = prog.createKernel("two_buffer_kernel") >> resources
      kernel.setArgs(inBuf,outBuf)

      val nanos = measureNanos(nRounds) {
        queue.enqueueRecordBufferWrite(inBuf)
        queue.enqueue1DRange(kernel, nWorkItems)
        queue.enqueueRecordBufferRead(outBuf,true)
      }
      if (showResult) printOutput("testTwoBufferCopy",nRounds,nWorkItems,nanos)
    }
  }

  //-------------------------------- two mapped buffers

  def testTwoBufferMapped (showResult: Boolean): Unit = {
    val nRounds = opts.rounds
    val nWorkItems = opts.workitems

    tryWithResource(new CloseStack) { resources =>
      val inBuf = context.createMappedRecordBuffer(nWorkItems, new TestInRecord(_)) >> resources
      val outBuf = context.createMappedRecordBuffer(nWorkItems, new TestOutRecord(_)) >> resources

      queue.executeMappedWithRecord(inBuf){ r=>
        r.a := 2
        r.b := 3
        r.c := 2
      }

      val prog = context.createAndBuildProgram(twoBufferKernelSrc) >> resources
      val kernel = prog.createKernel("two_buffer_kernel") >> resources
      kernel.setArgs(inBuf,outBuf)

      val nanos = measureNanos(nRounds) {
        queue.enqueue1DRange(kernel, nWorkItems)

        outBuf.enqueueMap(queue)
        // processing here
        outBuf.enqueueUnmap(queue)
        //queue.executeMapped(outBuf) { _.capacity }
      }
      if (showResult) printOutput("testTwoBufferMapped",nRounds,nWorkItems,nanos)
    }
  }

  //-------------------------------- one mapped buffer

  def testOneBufferMapped (showResult: Boolean): Unit = {
    val nRounds = opts.rounds
    val nWorkItems = opts.workitems

    tryWithResource(new CloseStack) { resources =>
      val inoutBuf = context.createMappedRecordBuffer(nWorkItems, new TestInOutRecord(_)) >> resources

      queue.executeMappedWithRecord(inoutBuf) { r =>
        r.a := 2
        r.b := 3
        r.c := 2
      }

      val prog = context.createAndBuildProgram(oneBufferKernelSrc) >> resources
      val kernel = prog.createKernel("one_buffer_kernel") >> resources
      kernel.setArgs(inoutBuf)

      val nanos = measureNanos(nRounds) {
        queue.enqueue1DRange(kernel, nWorkItems)
        queue.executeMapped(inoutBuf) { _.capacity }
      }
      if (showResult) printOutput("testOneBufferMapped",nRounds,nWorkItems,nanos)
    }
  }

  //--- benchmark infrastructure


  def getDevice = {
    CLPlatform.platforms(opts.platformIndex).devices(opts.deviceIndex)
  }

  def listDevices: Unit = {
    for ( (p,i) <- CLPlatform.platforms.zipWithIndex){
      println(s"platform [$i]: ${p.name}")
      for ( (d,j) <- p.devices.zipWithIndex) {
        println(s"    device [$j]: ${d.name}")
      }
    }
  }

  def initCL: Unit = {
    device = getDevice
    println(s"using $device")

    context = device.createContext
    queue = device.createCommandQueue(context)
  }

  def waitForInput: Unit = {
    print("hit any key to continue..")
    System.out.flush
    System.in.read
  }

  def disableChecks: Unit = {
    Configuration.DEBUG.set(false)
    Configuration.DEBUG_MEMORY_ALLOCATOR.set(false)
    Configuration.DEBUG_STACK.set(false)
    Configuration.DEBUG_FUNCTIONS.set(false)

    Configuration.DISABLE_CHECKS.set(true)
    Configuration.STACK_SIZE.set(128)
  }

  def runTest (f: (Boolean)=>Unit): Unit = {
    f(false) // warmup
    System.gc
    f(true)  // measure
  }

  def printOutput(test: String, nRounds: Int, nWorkItems: Int, nanos: Long): Unit = {
    println(f"$test%25.25s: $nRounds%7d rounds on $nWorkItems%7d items = ${(nanos/1000000).toInt}%6d msec")
  }


  def main (args: Array[String]): Unit = {
    opts = CliArgs(args){opts}.getOrElse{return}

    if (opts.listDevices) {
      listDevices
      return
    }

    if (opts.delay) waitForInput
    if (opts.noCheck) disableChecks

    opts.runOnly match {
      case None =>
        initCL
        runTest(testHost)
        runTest(testArrays)
        runTest(testTwoBufferCopy)
        runTest(testTwoBufferMapped)
        runTest(testOneBufferMapped)

      case Some("host") =>
        runTest(testHost)

      case Some("copy") =>
        initCL
        runTest(testTwoBufferCopy)

      case Some("map") =>
        initCL
        runTest(testTwoBufferMapped)

      case t => println(s"unknown test $t")
    }

    ifNotNull(queue){_.close}
    ifNotNull(context){_.close}
    ifNotNull(device){_.close}
  }
}
