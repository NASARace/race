package gov.nasa.race.share

import gov.nasa.race.common.JsonWriter
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for NodeList and NodeInfo
  */
class NodeListSpec extends AnyFlatSpec with RaceSpec {

  "a NodeListParser" should "read a known nodeList.json" in {
    val input = FileUtils.fileContentsAsString("src/resources/data/coordinator/nodeList.json").get
    val parser = new NodeListParser

    println(s"#-- parsing nodeList: $input")

    parser.parse(input.getBytes) match {
      case Some(nodeList) =>
        println("  result:")
        val w = new JsonWriter
        w.format(true)
        println(w.toJson(nodeList))

        // some sanity checks
        assert(nodeList.self.id == "/nodes/coordinator")
        assert(nodeList.upstreamNodes.isEmpty)
        assert(nodeList.peerNodes.isEmpty)
        assert(nodeList.downstreamNodes.size == 8)
        println("Ok.")

      case _ => fail("failed to parse node list")
    }
  }
}
