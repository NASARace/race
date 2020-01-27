package gov.nasa.race.common

import java.io.File

import gov.nasa.race.util.FileUtils

object StringSearchBenchmark {

  final val nRounds = 10000

  def main (args: Array[String]): Unit = {
    val input = FileUtils.fileContentsAsBytes(new File("src/test/resources/sfdps-msg.xml")).get

    //--- warmup
    println("warming up..")
    runBmSearch(input,true)
    runRegexSearch(input,true)
    runRegexSearchString(input,true)

    runBmSearch(input)
    runRegexSearch(input)
    runRegexSearchString(input)
  }

  def runBmSearch (input: Array[Byte], quiet: Boolean=false): Unit = {
    var i = 0
    var create: Long = 0
    var run: Long = 0
    var n = 0

    Runtime.getRuntime.gc

    while (i < nRounds) {
      val t0 = System.nanoTime
      val bmSearch = new BMSearch("<flight ")
      val t1 = System.nanoTime
      var idx = bmSearch.indexOfFirstIn(input)
      n = 0
      while (idx >= 0) {
        n += 1
        val iOff = idx + bmSearch.patternLength
        val iLen = input.length-iOff
        idx = bmSearch.indexOfFirstIn(input, iOff, iLen)
      }
      val t2 = System.nanoTime

      create += (t1-t0)
      run += (t2-t1)
      i += 1
    }

    if (!quiet) {
      println(s"--- bmSearch found $n matches")
      println(s"creation: ${create / nRounds} ns")
      println(s"search:   ${run / nRounds} ns")
    }
  }

  def runRegexSearch (input: Array[Byte], quiet: Boolean = false): Unit = {
    val cs = ConstAsciiSlice(input)
    var i = 0
    var create: Long = 0
    var run: Long = 0
    var n = 0

    Runtime.getRuntime.gc

    while (i < nRounds) {
      val t0 = System.nanoTime
      val rSearch = "<flight ".r
      val t1 = System.nanoTime
      val it = rSearch.findAllIn(cs)
      n = it.size
      val t2 = System.nanoTime

      create += (t1 - t0)
      run += (t2 - t1)
      i += 1
    }

    if (!quiet) {
      println(s"--- rSearch found $n matches")
      println(s"creation: ${create / nRounds} ns")
      println(s"search:   ${run / nRounds} ns")
    }
  }

  def runRegexSearchString (input: Array[Byte], quiet: Boolean = false): Unit = {
    val str = new String(input)
    var i = 0
    var create: Long = 0
    var run: Long = 0
    var n = 0

    Runtime.getRuntime.gc

    while (i < nRounds) {
      val t0 = System.nanoTime
      val rSearch = "<flight ".r
      val t1 = System.nanoTime
      val it = rSearch.findAllIn(str)
      n = it.size
      val t2 = System.nanoTime

      create += (t1 - t0)
      run += (t2 - t1)
      i += 1
    }

    if (!quiet) {
      println(s"--- rSearch/string found $n matches")
      println(s"creation: ${create / nRounds} ns")
      println(s"search:   ${run / nRounds} ns")
    }
  }
}
