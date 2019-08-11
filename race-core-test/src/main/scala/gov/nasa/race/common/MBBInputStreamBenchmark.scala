/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.io.{File, FileInputStream, FileOutputStream, InputStream}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

/**
  * a benchmark for MappedByteBufferInputStreams
  */
object MBBInputStreamBenchmark {

  //val line = "0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345,0.12345\n".getBytes
  val   line = "ljsdflkj-03-40[g-0asi5;lak095=g;o35l,c';s[ju-jd;hn4letlauo503805-hdlaut;0ulhatt\n".getBytes // make it a bit more random

  val nLines = 50000000
  val uncompressedLength: Long = nLines * line.length

  val mapLen = 1024*1024*16 // increasing map sizes slightly improve read times

  val zipBufLen: Int = 64 * 1024

  def createTestFile: File = {
    val f = File.createTempFile("mbbtest",null)

    val fs = new FileOutputStream(f)

    //--- each record has 70 bytes
    for (i <- 0 until nLines) fs.write(line)
    fs.close
    f
  }

  def createCompressedTestFile: File = {
    val f = File.createTempFile("mbbtest-compressed",null)

    val fs = new FileOutputStream(f)
    val zs = new GZIPOutputStream(fs)

    //--- each record has 70 bytes
    for (i <- 0 until nLines) zs.write(line)
    zs.close
    f
  }

  def read (is: InputStream): Long = {
    val buf = new Array[Byte](line.length)
    var n: Long = 0

    val t0 = System.currentTimeMillis
    var len: Int = is.read(buf)
    while(len > 0){
      n += len
      len = is.read(buf)
    }
    val t1 = System.currentTimeMillis

    is.close
    //assert(n == uncompressedLength)

    t1 - t0
  }

  def readMBB (testFile: File): Long = {
    read(new MappedByteBufferInputStream(testFile.getPath))
  }

  def readCompressedMBB (testFile: File): Long = {
    read(new GZIPInputStream(new MappedByteBufferInputStream(testFile.getPath, mapLen), zipBufLen))
  }

  def readFIS (testFile: File): Long = {
    read(new FileInputStream(testFile))
  }

  def readCompressedFIS (testFile: File): Long = {
    read(new GZIPInputStream(new FileInputStream(testFile), zipBufLen))
  }

  def readUncompressed (testFile: File, nRounds: Int): Unit = {
    //val testFile = createTestFile
    val len = testFile.length / (1024*1024)

    println(s"\nreading uncompressed $len MB $testFile ..")

    for (i <- 0 until nRounds) {
      Runtime.getRuntime.gc
      println(f"MappedByteBufferInputStream: ${readMBB(testFile)}%5d msec")

      Runtime.getRuntime.gc
      println(f"FileInputStream:             ${readFIS(testFile)}%5d msec")
    }

    //testFile.delete
  }

  def readCompressed (testFile: File, nRounds: Int): Unit = {
    //val testFile = createCompressedTestFile
    val len = testFile.length / (1024*1024)

    println(s"\nreading compressed $len MB $testFile ..")

    for (i <- 0 until nRounds) {
      Runtime.getRuntime.gc
      println(f"FileInputStream:             ${readCompressedFIS(testFile)}%5d msec")

      Runtime.getRuntime.gc
      println(f"MappedByteBufferInputStream: ${readCompressedMBB(testFile)}%5d msec")
    }

    //testFile.delete
  }

  def main (args: Array[String]): Unit = {
    if (args.length == 0) {
      println("usage: MBBInputStreamBenchmark <testfile> <rounds>")
      return
    }
    val nRounds = if (args.length > 1) Integer.parseInt(args(1)) else 1

    val testFile = new File(args(0))

    if (testFile.getName.endsWith(".gz")) {
      readCompressed(testFile, nRounds)
    } else {
      readUncompressed(testFile, nRounds)
    }
  }
}
