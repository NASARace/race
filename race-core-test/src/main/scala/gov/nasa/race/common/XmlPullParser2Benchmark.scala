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

import java.io.File

import gov.nasa.race.util.{FileUtils, XmlPullParser}
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
    val messageCollection = ConstAsciiSlice("ns5:MessageCollection")
    val flightIdentification = ConstAsciiSlice("flightIdentification")
    val aircraftIdentification = ConstAsciiSlice("aircraftIdentification")
    val flight = ConstAsciiSlice("flight")
    val positionTime = ConstAsciiSlice("positionTime") // attr
    val pos =ConstAsciiSlice("pos")
    val enRoute = ConstAsciiSlice("enRoute")
    val position = ConstAsciiSlice("position")
    val location = ConstAsciiSlice("location")

    val slicer = new SliceSplitter(' ')
    val valueSlice = MutAsciiSlice.empty

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
                if (slicer.hasNext) lat = slicer.next(valueSlice).toDouble
                if (slicer.hasNext) lon = slicer.next(valueSlice).toDouble
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
    val valueSlice = MutAsciiSlice.empty
    var nFlights = 0

    //--- attributes and ancestor elements
    val aircraftIdentification = ConstAsciiSlice("aircraftIdentification")
    val enRoute = ConstAsciiSlice("enRoute")
    val location = ConstAsciiSlice("location")
    val positionTime = ConstAsciiSlice("positionTime")
    val flight = ConstAsciiSlice("flight")

    @inline def parseFlight: Unit = {
      var done = false // to implement non-local returns

      // here we can keep cache vars at the terminal level, i.e. don't have to reset
      var id: String = null
      var lat, lon: Double = 0.0
      var dtg: String = null

      @inline def parseFlightStartTags (data: Array[Byte], off: Int, len: Int): Unit = {

        @inline def process_flightIdentification = {
          if (parser.parseAttr(aircraftIdentification)) id = parser.attrValue.intern
        }
        @inline def process_pos = {
          if (parser.tagHasParent(location)) {
            if (parser.parseContent && parser.getNextContentString) {
              slicer.setSource(parser.contentString)
              if (slicer.hasNext) lat = slicer.next(valueSlice).toDouble
              if (slicer.hasNext) lon = slicer.next(valueSlice).toDouble
            }
          }
        }
        @inline def process_position = {
          if (parser.tagHasParent(enRoute)) {
            if (parser.parseAttr(positionTime)) dtg = parser.attrValue.toString
          }
        }

        //--- automatically generated part
        @inline def match_flightIdentification = { len==20 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116 && data(off+6)==73 && data(off+7)==100 && data(off+8)==101 && data(off+9)==110 && data(off+10)==116 && data(off+11)==105 && data(off+12)==102 && data(off+13)==105 && data(off+14)==99 && data(off+15)==97 && data(off+16)==116 && data(off+17)==105 && data(off+18)==111 && data(off+19)==110 }
        @inline def match_pos = { len>=3 && data(off)==112 && data(off+1)==111 && data(off+2)==115 }
        @inline def match_pos_len = { len==3 }
        @inline def match_position = { len==8 && data(off+3)==105 && data(off+4)==116 && data(off+5)==105 && data(off+6)==111 && data(off+7)==110 }

        if (match_flightIdentification) {
          process_flightIdentification
        } else if (match_pos) {
          if (match_pos_len) {
            process_pos
          } else if (match_position) {
            process_position
          }
        }
      }

      @inline def parseFlightEndTags (data: Array[Byte], off: Int, len: Int): Unit = {
        @inline def process_flight = {
          if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
            nFlights += 1
            //println(s"$nFlights: $id, $dtg, $lat, $lon")
            done = true // return from parseFlight but don't stop parsing
          }
        }
        @inline def process_ns5$MessageCollection = {
          assert( nFlights == 100)
          nFlights = 0
        }

        //--- automatically generated part
        @inline def match_flight = { len==6 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116 }
        @inline def match_ns5$MessageCollection = { len==21 && data(off)==110 && data(off+1)==115 && data(off+2)==53 && data(off+3)==58 && data(off+4)==77 && data(off+5)==101 && data(off+6)==115 && data(off+7)==115 && data(off+8)==97 && data(off+9)==103 && data(off+10)==101 && data(off+11)==67 && data(off+12)==111 && data(off+13)==108 && data(off+14)==108 && data(off+15)==101 && data(off+16)==99 && data(off+17)==116 && data(off+18)==105 && data(off+19)==111 && data(off+20)==110 }

        if (match_flight) {
          process_flight
        } else if (match_ns5$MessageCollection) {
          process_ns5$MessageCollection
        }
      }

      while (parser.parseNextTag) {
        val tag = parser.tag

        if (parser.isStartTag) {
          parseFlightStartTags(tag.data,tag.off,tag.len)
        } else {
          parseFlightEndTags(tag.data,tag.off,tag.len)
          if (done) return
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
