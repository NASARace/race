package gov.nasa.race.cl

import java.nio.ByteBuffer

import gov.nasa.race.common._
import gov.nasa.race.common.BufferRecord
import gov.nasa.race.tryWithResource

/**
  * benchmark to compare OpenCL data management strategies
  */

object Benchmarks {
  class TestInRecord (buffer: ByteBuffer) extends BufferRecord(20,buffer) {
    val a = double(0)
    val b = double(8)
    val c = int(16)
  }

  class TestOutRecord (buffer: ByteBuffer) extends BufferRecord(24, buffer) {
    val x = double(0)
    val y = double(8)
    val z = double(16)
  }

  class TestInOutRecord (buffer: ByteBuffer) extends BufferRecord(44,buffer) {
    val a = double(0)
    val b = double(8)
    val c = int(16)

    val x = double(20)
    val y = double(28)
    val z = double(36)
  }

  val nEntries = 10000 // number of items in buffers
  val nRounds = 10000  // number of computations

  val twoBufferKernelSrc =
    """
      |  typedef struct __attribute__ ((packed)) _test_in_record {
      |    double a;
      |    double b;
      |    int c;
      |  } test_in_record_t;
      |
      |  typedef struct __attribute__ ((packed)) _test_out_record {
      |    double x;
      |    double y;
      |    double z;
      |  } test_out_record_t;
      |
      |  __kernel void two_buffer_kernel (__global test_in_record_t* in, __global test_out_record_t* out) {
      |     unsigned int i = get_global_id(0);
      |
      |     out[i].x = in[i].a * in[i].c;
      |     out[i].y = in[i].b * in[i].c;
      |     out[i].z = in[i].a + in[i].b;
      |
      |     //printf("[%d]: %f %f %d -> %f %f %f\n", i, in[i].a, in[i].b, in[i].c, out[i].x, out[i].y, out[i].z);
      |  }
    """.stripMargin

  val oneBufferKernelSrc =
    """
      |  typedef struct __attribute__ ((packed)) _test_inout_record {
      |    double a;
      |    double b;
      |    int c;
      |
      |    double x;
      |    double y;
      |    double z;
      |  } test_inout_record_t;
      |
      |  __kernel void one_buffer_kernel (__global test_inout_record_t* rec) {
      |     unsigned int i = get_global_id(0);
      |
      |     rec[i].x = rec[i].a * rec[i].c;
      |     rec[i].y = rec[i].b * rec[i].c;
      |     rec[i].z = rec[i].a + rec[i].b;
      |  }
    """.stripMargin

  val arrayKernelSrc =
    """
      |  __kernel void array_kernel (__global double* a, __global double* b, __global int* c,
      |                              __global double* x, __global double* y, __global double* z) {
      |    unsigned int i = get_global_id(0);
      |
      |    x[i] = a[i] * c[i];
      |    y[i] = b[i] * c[i];
      |    z[i] = a[i] + b[i];
      |  }
    """.stripMargin

  //--------------------------------- explicit objects, CPU only

  class InObj (val a: Double, val b: Double, val c: Int)
  class OutObj (var x: Double, var y: Double, var z: Double)

  def compute (in: InObj, out: OutObj): Unit = {
    out.x = in.a * in.c
    out.y = in.b * in.c
    out.z = in.a + in.b
  }

  def testBaseline: Unit = {
    val aIn = new Array[InObj](nEntries)
    val aOut = new Array[OutObj](nEntries)

    for (i <- 0 until nEntries) aIn(i) = new InObj(2,3,2)
    for (i <- 0 until nEntries) aOut(i) = new OutObj(0,0,0)

    val nanos = measureNanos(nRounds) {
      for (i <- 0 until nEntries) { compute( aIn(i), aOut(i)) }
    }
    println(f"testBaseline $nRounds rounds on $nEntries items: ${(nanos/1000000).toInt}msec")

  }

  //-------------------------------------- arrays

  def testArrays: Unit = {
    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice >> resources
      //println(s"got $device")

      val context = device.createContext >> resources
      val queue = device.createJobCommandQueue(context) >> resources

      val a = context.createArrayRBuffer[Double](nEntries) >> resources
      val b = context.createArrayRBuffer[Double](nEntries) >> resources
      val c = context.createArrayRBuffer[Int](nEntries) >> resources

      val x = context.createArrayWBuffer[Double](nEntries) >> resources
      val y = context.createArrayWBuffer[Double](nEntries) >> resources
      val z = context.createArrayWBuffer[Double](nEntries) >> resources

      for (i <- 0 until nEntries) {
        a.data(i) = 2
        b.data(i) = 3
        c.data(i) = 2
      }

      val prog = context.createAndBuildProgram(arrayKernelSrc) >> resources
      val kernel = prog.createKernel("array_kernel") >> resources
      kernel.setArgs(a,b,c,x,y,z)

      val nanos = measureNanos(nRounds) {
        queue.enqueueArrayBufferWrite(a)
        queue.enqueueArrayBufferWrite(b)
        queue.enqueueArrayBufferWrite(c)

        queue.enqueue1DRange(kernel, nEntries)

        queue.enqueueArrayBufferRead(x)
        queue.enqueueArrayBufferRead(y)
        queue.enqueueArrayBufferRead(z, true)
      }
      println(f"testArrays $nRounds rounds on $nEntries items: ${(nanos/1000000).toInt}msec")
    }
  }

  //-------------------------------------- two buffers, copy

  def testTwoBufferCopy: Unit = {
    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice >> resources
      //println(s"got $device")

      val context = device.createContext >> resources
      val queue = device.createJobCommandQueue(context) >> resources

      val inBuf = context.createRecordRBuffer(nEntries, new TestInRecord(_)) >> resources
      val outBuf = context.createRecordWBuffer(nEntries, new TestOutRecord(_)) >> resources

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
        queue.enqueue1DRange(kernel, nEntries)
        queue.enqueueRecordBufferRead(outBuf,true)
      }
      println(f"testCopy $nRounds rounds on $nEntries items: ${(nanos/1000000).toInt}msec")
    }
  }

  //-------------------------------- two mapped buffers

  def testTwoBufferMapped: Unit = {
    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice >> resources
      //println(s"got $device")

      val context = device.createContext >> resources
      val queue = device.createJobCommandQueue(context) >> resources

      val inBuf = context.createMappedRecordBuffer(nEntries, new TestInRecord(_)) >> resources
      val outBuf = context.createMappedRecordBuffer(nEntries, new TestOutRecord(_)) >> resources

      queue.executeMappedWithRecord(inBuf){ r=>
        r.a := 2
        r.b := 3
        r.c := 2
      }

      val prog = context.createAndBuildProgram(twoBufferKernelSrc) >> resources
      val kernel = prog.createKernel("two_buffer_kernel") >> resources
      kernel.setArgs(inBuf,outBuf)

      val nanos = measureNanos(nRounds) {
        queue.enqueue1DRange(kernel, nEntries)
        queue.executeMapped(outBuf) { _.capacity }
      }
      println(f"testTwoMapped $nRounds rounds on $nEntries items: ${(nanos/1000000).toInt}msec")
    }
  }

  //-------------------------------- one mapped buffer

  def testOneBufferMapped: Unit = {
    tryWithResource(new CloseStack) { resources =>
      val device = CLPlatform.preferredDevice >> resources
      //println(s"got $device")

      val context = device.createContext >> resources
      val queue = device.createJobCommandQueue(context) >> resources

      val inoutBuf = context.createMappedRecordBuffer(nEntries, new TestInOutRecord(_)) >> resources

      queue.executeMappedWithRecord(inoutBuf) { r =>
        r.a := 2
        r.b := 3
        r.c := 2
      }

      val prog = context.createAndBuildProgram(oneBufferKernelSrc) >> resources
      val kernel = prog.createKernel("one_buffer_kernel") >> resources
      kernel.setArgs(inoutBuf)

      val nanos = measureNanos(nRounds) {
        queue.enqueue1DRange(kernel, nEntries)
        queue.executeMapped(inoutBuf) { _.capacity }
      }
      println(f"testOneMapped $nRounds rounds on $nEntries items: ${(nanos/1000000).toInt}msec")
    }
  }


  def main (args: Array[String]) = {
    System.gc
    testArrays

    System.gc
    testTwoBufferCopy

    System.gc
    testTwoBufferMapped

    System.gc
    testOneBufferMapped

    System.gc
    testBaseline
  }
}
