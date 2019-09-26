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

    prompt("\n---- hit any key to run new tree parser..")
    runXmlPullParser2Tree(msg)
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

    def parseFlight: Unit = {
      // here we can keep cache vars at the terminal level, i.e. don't have to reset
      var id: String = null
      var lat, lon: Double = 0.0
      var dtg: String = null

      while (parser.parseNextTag) {
        val tag = parser.tag
        val data = tag.data
        val i = tag.offset
        val len = tag.length

        if (parser.isStartTag) {
          //if (len > 0) { //empty tags are not valid XML
            if (data(i) == 'f'){ // flightIdentification
              if (len == 20 && data(i+1)=='l' && data(i+2)=='i' && data(i+3)=='g' && data(i+4)=='h' && data(i+5)=='t' && data(i+6)=='I' && data(i+7)=='d' && data(i+8)=='e' && data(i+9)=='n' && data(i+10)=='t' && data(i+11)=='i' && data(i+12)=='f' && data(i+13)=='i' && data(i+14)=='c' && data(i+15)=='a' && data(i+16)=='t' && data(i+17)=='i' && data(i+18)=='o' && data(i+19)=='n'){
                if (parser.parseAttr(aircraftIdentification)) id = parser.attrValue.intern
              }
            } else if (data(i) == 'p') { // position | pos
              if ((len > 2) && (data(i+1) == 'o') && (data(i+2) == 's')) {
                if (len == 3) { // pos
                  if (parser.tagHasParent(location)) {
                    if (parser.parseContent && parser.getNextContentString) {
                      slicer.setSource(parser.contentString)
                      if (slicer.hasNext) lat = slicer.next.toDouble
                      if (slicer.hasNext) lon = slicer.next.toDouble
                    }
                  }
                } else if (len == 8 && data(i + 3) == 'i' && data(i + 4) == 't' && data(i + 5) == 'i' && data(i + 6) == 'o' && data(i + 7) == 'n') {
                  if (parser.tagHasParent(enRoute)) {
                    if (parser.parseAttr(positionTime)) dtg = parser.attrValue.toString
                  }
                }
              }
            }
          //}

        } else { // end tag
          //if (len > 0){  // empty tags are not valid XML
            if (data(i) == 'f') { // /flight
              if ((len == 6) && data(i+1)=='l' && data(i+2)=='i' && data(i+3)=='g' && data(i+4)=='h' && data(i+5)=='t') {
                if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
                  nFlights += 1
                  //println(s"$nFlights: $id, $dtg, $lat, $lon")
                  return
                }
              }
            } else if (data(i) == 'm') { // messageCollection
              if ((len==17) && data(i+1)=='e' && data(i+2)=='s' && data(i+3)=='s' && data(i+4)=='a' && data(i+5)=='g' && data(i+6)=='e' && data(i+7)=='C' && data(i+8)=='o' && data(i+9)=='l' && data(i+10)=='l' && data(i+11)=='e' && data(i+12)=='c' && data(i+13)=='t' && data(i+14)=='i' && data(i+15)=='o' && data(i+16)=='n'){
                assert( nFlights == 100)
                nFlights = 0
              }
            }
          //}
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
