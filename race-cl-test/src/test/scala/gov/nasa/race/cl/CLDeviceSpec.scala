package gov.nasa.race.cl

import gov.nasa.race.test.RaceSpec
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

class CLDeviceSpec extends FlatSpec with RaceSpec with BeforeAndAfterAll {

  "a GPU CLDevice" should "execute a 1D kernel to add array elements" in {

    val src =
      """
         __kernel void add (__global int* a, __global int* b, __global int* c) {
           unsigned int i = get_global_id(0);
           c[i] = a[i] + b[i];
         }
      """

    val device = CLPlatform.preferredDevice
    println(s"got $device")

    val context =CLContext(device)
    val queue = device.createCommandQueue(context)

    //--- change
    val a = Array[Int](1, 2, 3, 4, 5)
    val aBuf = device.createIntArrayCRBuffer(a)

    val b = Array[Int](5, 4, 3, 2, 1)
    val bBuf = device.createIntArrayCRBuffer(b)

    val cBuf = device.createIntArrayCWBuffer(a.length)

    val prog = device.createAndBuildProgram(src)
    val kernel = prog.createKernel("add")

    kernel.setArgs(aBuf, bBuf, cBuf)
    device.enqueue1DRange(kernel, a.length)
    device.enqueueRead(cBuf)
    //device.finish

    println(s"result: ${cBuf.data.mkString(",")}")


    kernel.release
    prog.release
    aBuf.release
    bBuf.release
    cBuf.release
    device.release
  }
}
