package gov.nasa.race.common

import java.io.File

import gov.nasa.race.util.{FileUtils, XmlPullParser}
import gov.nasa.race.test._

object XmlPullParser2Benchmark {

  var nRounds = 10000

  def main (args: Array[String]): Unit = {
    if (args.nonEmpty){
      nRounds = args(0).toInt
    }

    val msg = FileUtils.fileContentsAsBytes(new File("src/test/resources/sfdps-msg.xml")).get
    println(s"byte size of input message: ${msg.length}")

    gc
    println("\n---- hit any key to run old parser..")
    System.in.read
    runOldPullParser(msg)

    gc
    println("\n---- hit any key to run new parser..")
    System.in.read
    runXmlPullParser2(msg)
  }

  def runXmlPullParser2 (msg: Array[Byte]): Unit = {
    println(s"-- parsing ${nRounds}x using XmlPullParser2")
    val parser = new UTF8XmlPullParser2

    //--- tags, attrs and value extractors we need
    val flightIdentification = Slice("flightIdentification")
    val aircraftIdentification = Slice("aircraftIdentification")
    val flight = Slice("flight")
    val positionTime = Slice("positionTime") // attr
    val pos =Slice("pos")
    val enRoute = Slice("enRoute")
    val position = Slice("position")
    val location = Slice("location")
    val slicer = new SliceSplitter(' ')

    var nFlights = 0

    def parseFlight: Unit = {
      var id: String = null
      var lat,lon: Double = 0.0
      var dtg: String = null

      while (parser.parseNextTag) {
        if (parser.isStartTag) {
          val tag = parser.tag

          if (tag == flightIdentification) {
            if (parser.parseAttr(aircraftIdentification)) id = parser.attrValue.intern

          } else if (tag == position){
            if (parser.tagHasParent(enRoute)) {
              if (parser.parseAttr(positionTime)) dtg = parser.attrValue.toString
            }

          } else if (tag == pos) {
            if (parser.tagHasParent(location)) {
              if (parser.parseContent && parser.getNextContentString) {
                slicer.setSource(parser.contentString)
                if (slicer.hasNext) lat = slicer.next.toDouble
                if (slicer.hasNext) lon = slicer.next.toDouble
              }
            }
          }
        } else {
          if (parser.tag == flight) {
            if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
              nFlights += 1
              //println(s"$nFlights: $id, $dtg, $lat, $lon")
              return
            }
          }
        }
      }
    }

    var j = 0
    val g0 = gcCount(0)
    val gt0 = gcMillis(0)
    val m0 = usedHeapMemory
    val t0 = System.nanoTime
    while (j < nRounds) {
      if (parser.initialize(msg)) {
        nFlights = 0
        while (parser.parseNextTag) {
          if (parser.tag == flight && parser.isStartTag) parseFlight
        }
      }
      j += 1
    }
    val t1 = System.nanoTime
    val m1 = usedHeapMemory
    val gt1 = gcMillis(0)
    val g1 = gcCount(0)

    println(s"  ${(t1 - t0)/1000000} msec")
    println(s"  ${(m1 - m0)/1024} kB")
    println(s"  ${g1 - g0} gc cycles, ${gt1 - gt0} msec")
  }

  def runOldPullParser (msg: Array[Byte]): Unit = {
    val msgChars = new String(msg).toCharArray
    var nFlights = 0
    println(s"-- parsing ${nRounds}x using old XmlPullParser")
    val parser = new XmlPullParser

    def parseFlight: Unit = {
      var id: String = null
      var lat,lon: Double = 0.0
      var dtg: String = null

      while (parser.parseNextElement){
        if (parser.isStartElement){
          parser.tag match {
            case "flightIdentification" =>
              id = parser.readAttribute("aircraftIdentification")
            case "position" =>
              if (parser.hasParent("enRoute")) dtg = parser.readAttribute("positionTime")
            case "pos" =>
              if (parser.hasParent("location")) {
                lat = parser.readDouble
                lon = parser.readNextDouble
              }
            case _ => // ignore
          }
        } else { // end tag
          if (parser.tag == "flight") {
            if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
              nFlights += 1
              //println(s"$nFlights: $id, $dtg, $lat, $lon")
              return
            }
          }
        }
      }
    }

    var j = 0
    val g0 = gcCount(0)
    val gt0 = gcMillis(0)
    val m0 = usedHeapMemory
    val t0 = System.nanoTime
    while (j < nRounds) {
      parser.initialize(msgChars)
      nFlights = 0
      while (parser.parseNextElement) {
        if (parser.tag == "flight" && parser.isStartElement) parseFlight
      }
      j += 1
    }
    val t1 = System.nanoTime
    val m1 = usedHeapMemory
    val gt1 = gcMillis(0)
    val g1 = gcCount(0)

    println(s"  ${(t1 - t0)/1000000} msec")
    println(s"  ${(m1 - m0)/1024} kB")
    println(s"  ${g1 - g0} gc cycles, ${gt1 - gt0} msec")
  }
}
