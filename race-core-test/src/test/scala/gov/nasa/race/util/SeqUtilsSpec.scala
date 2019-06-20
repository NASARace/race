package gov.nasa.race.util

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
  * regression tests for gov.nasa.race.util.SeqUtils
  */
class SeqUtilsSpec extends AnyFlatSpec with RaceSpec {

  behavior of "SeqUtils"

  "quickSelect" should "reproduce known median of test data" in {
    val a = Array(8,2,5,4,3,9,1,7)  // unsorted test array
    println("unsorted array: " + a.mkString(","))

    val e4 = SeqUtils.quickSelect(a, 3) // index 3 is 4th lowest element - operation changes a-order
    println("sorted array:   " + a.sorted.mkString(","))
    println(s"4th sorted element: $e4")

    e4 shouldBe 4
  }
}
