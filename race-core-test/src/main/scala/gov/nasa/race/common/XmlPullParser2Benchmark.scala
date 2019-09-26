package gov.nasa.race.common

import java.io.File

import gov.nasa.race.util.{FileUtils, XmlPullParser}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.test._

object XmlPullParser2Benchmark {

  var nRounds = 10000

  def main (args: Array[String]): Unit = {
    if (args.nonEmpty){
      nRounds = args(0).toInt
    }

    val msg = FileUtils.fileContentsAsBytes(new File("src/test/resources/sfdps-msg.xml")).get
    println(s"byte size of input message: ${msg.length}")

    prompt("\n---- hit any key to run old parser..")
    runOldPullParser(msg)

    prompt("\n---- hit any key to run new parser..")
    runXmlPullParser2(msg)
  }

  def prompt (msg: String): Unit = {
    println(msg)
    System.in.read
  }

  def measure (rounds: Int)(f: =>Unit): Unit = {
    gc

    var j = rounds
    val g0 = gcCount(0)
    val gt0 = gcMillis(0)
    val m0 = usedHeapMemory
    val t0 = System.nanoTime

    while (j > 0) {
      f
      j -= 1
    }

    val t1 = System.nanoTime
    val m1 = usedHeapMemory
    val gt1 = gcMillis(0)
    val g1 = gcCount(0)

    println(s"  ${(t1 - t0)/1000000} msec")
    println(s"  ${(m1 - m0)/1024} kB")
    println(s"  ${g1 - g0} gc cycles, ${gt1 - gt0} msec")
  }


  def runXmlPullParser2(msg: Array[Byte]): Unit = {
    println(s"-- parsing ${nRounds}x using XmlPullParser2")
    val parser = new UTF8XmlPullParser2

    //--- tags, attrs and value extractors we need
    val messageCollection = Slice("ns5:MessageCollection")
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
      // here we can keep cache vars at the terminal level, i.e. don't have to reset
      var id: String = null
      var lat,lon: Double = 0.0
      var dtg: String = null

      while (parser.parseNextTag) {
        val tag = parser.tag

        if (parser.isStartTag) {  // start tags

          if (tag == flightIdentification) {
            if (parser.parseAttr(aircraftIdentification)) id = parser.attrValue.intern

          } else if (tag == position) {
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

        } else { // end tags
          if (tag == flight) {
            if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
              nFlights += 1
              //println(s"$nFlights: $id, $dtg, $lat, $lon")
              return
            }

          } else if (tag == messageCollection){
            assert( nFlights == 100)
            nFlights = 0
          }
        }
      }
    }

    measure(nRounds) {
      if (parser.initialize(msg)) {
        while (parser.parseNextTag) {
          if (parser.tag == flight && parser.isStartTag) parseFlight
        }
      }
    }
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
        val tag = parser.tag

        if (parser.isStartElement){
          tag match {
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

        } else { // end tags
          if (tag == "flight") {
            if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
              nFlights += 1
              //println(s"$nFlights: $id, $dtg, $lat, $lon")
              return
            }
          }
        }
      }
    }

    measure(nRounds) {
      parser.initialize(msgChars)
      while (parser.parseNextElement) {
        val tag = parser.tag
        if (parser.isStartElement){
          if (tag == "flight") parseFlight
        } else {
          if (tag == "ns5:MessageCollection") {
            assert( nFlights == 100)
            nFlights = 0
          }
        }
      }
    }
  }
}
