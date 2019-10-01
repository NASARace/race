package gov.nasa.race.common

import java.io.File

import gov.nasa.race.util.{FileUtils, XmlPullParser}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.test._

object XmlPullParser2Benchmark {

  var nRounds = 10000
  val sequence = Array("old","new","trie")

  def main (args: Array[String]): Unit = {

    if (args.nonEmpty){
      nRounds = args(0).toInt

      args.size match {
        case 2 => sequence(0) = args(1); sequence(1) = ""; sequence(2) = ""
        case 3 => sequence(0) = args(1); sequence(1) = args(2); sequence(2) = ""
        case 4 => sequence(0) = args(1); sequence(1) = args(2); sequence(2) = args(3)
        case _ =>
      }
    }

    val msg = FileUtils.fileContentsAsBytes(new File("src/test/resources/sfdps-msg.xml")).get
    println(s"byte size of input message: ${msg.length}")

    sequence.foreach { cmd =>
      cmd match {
        case "old" =>
          prompt("\n---- hit any key to run old parser..")
          runOldPullParser(msg)
        case "new" =>
          prompt("\n---- hit any key to run new parser..")
          runXmlPullParser2(msg)
        case "trie" =>
          prompt("\n---- hit any key to run new trie parser..")
          runXmlPullParser2Tree(msg)
        case "" => // ignore
        case unknown => println(s"unknown command: $unknown")
      }
    }
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

  def runXmlPullParser2Tree (msg: Array[Byte]): Unit = {
    println(s"-- parsing ${nRounds}x using XmlPullParser2 tree branching")
    val parser = new UTF8XmlPullParser2

    val slicer = new SliceSplitter(' ')
    var nFlights = 0

    //--- attributes and ancestor elements
    val aircraftIdentification = Slice("aircraftIdentification")
    val enRoute = Slice("enRoute")
    val location = Slice("location")
    val positionTime = Slice("positionTime")
    val flight = Slice("flight")

    @inline def parseFlight: Unit = {
      // here we can keep cache vars at the terminal level, i.e. don't have to reset
      var id: String = null
      var lat, lon: Double = 0.0
      var dtg: String = null

      @inline def parseFlightStartTags (data: Array[Byte], off: Int, len: Int): Unit = {
        // matcher generated by gov.nasa.race.tool.StringMatchGenerator
        if (len == 20 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116 && data(off+6)==73 && data(off+7)==100 && data(off+8)==101 && data(off+9)==110 && data(off+10)==116 && data(off+11)==105 && data(off+12)==102 && data(off+13)==105 && data(off+14)==99 && data(off+15)==97 && data(off+16)==116 && data(off+17)==105 && data(off+18)==111 && data(off+19)==110) {
          // flightIdentification
          if (parser.parseAttr(aircraftIdentification)) id = parser.attrValue.intern

        } else if (len >= 3 && data(off)==112 && data(off+1)==111 && data(off+2)==115) {
          if (len == 3) {
            // pos
            if (parser.tagHasParent(location)) {
              if (parser.parseContent && parser.getNextContentString) {
                slicer.setSource(parser.contentString)
                if (slicer.hasNext) lat = slicer.next.toDouble
                if (slicer.hasNext) lon = slicer.next.toDouble
              }
            }
          } else if (len == 8 && data(off+3)==105 && data(off+4)==116 && data(off+5)==105 && data(off+6)==111 && data(off+7)==110) {
            // position
            if (parser.tagHasParent(enRoute)) {
              if (parser.parseAttr(positionTime)) dtg = parser.attrValue.toString
            }
          }
        }
      }

      @inline def parseFlightEndTags (data: Array[Byte], off: Int, len: Int): Unit = {
        // matcher generated by gov.nasa.race.tool.StringMatchGenerator
        if (len == 6 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116) {
          // flight
          if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
            nFlights += 1
            //println(s"$nFlights: $id, $dtg, $lat, $lon")
            return
          }
        } else if (len == 17 && data(off)==109 && data(off+1)==101 && data(off+2)==115 && data(off+3)==115 && data(off+4)==97 && data(off+5)==103 && data(off+6)==101 && data(off+7)==67 && data(off+8)==111 && data(off+9)==108 && data(off+10)==108 && data(off+11)==101 && data(off+12)==99 && data(off+13)==116 && data(off+14)==105 && data(off+15)==111 && data(off+16)==110) {
          // messageCollection
          assert( nFlights == 100)
          nFlights = 0
        }
      }

      while (parser.parseNextTag) {
        val tag = parser.tag

        if (parser.isStartTag) {
          parseFlightStartTags(tag.data,tag.offset,tag.length)
        } else {
          parseFlightEndTags(tag.data,tag.offset,tag.length)
        }
      }
    }

    measure(nRounds) {
      if (parser.initialize(msg)) {
        while (parser.parseNextTag) {
          if (parser.isStartTag && parser.tag == flight) parseFlight
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
