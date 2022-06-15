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

import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for XmlPullParser2
  */
class XmlPullParser2Spec extends AnyFlatSpec with RaceSpec {

  val testMsg =
    """
      |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      |<top>
      |   <name><![CDATA[Track 1]]></name>
      |   <middle>
      |       <bottom1 attr1="123 < 321" attr2="whatdoiknow" attr3="skip this"/>
      |       <number> 1.234 <!-- in [fops/brizl] --> </number>
      |       <!-- this is ignored -->
      |       <bottom2> blah </bottom2>
      |   </middle>
      |</top>
      |""".stripMargin


  "a XmlPullParser2" should "print well-formed XML" in {
    val parser = new BufferedStringXmlPullParser2
    parser.initialize(testMsg)
    parser.printOn(System.out)
  }


  "a XmlPullParser2" should "extract known values from a test message" in {
    val parser = new BufferedStringXmlPullParser2
    parser.initialize(testMsg)

    //--- the parsed tags/attrs
    val top = ConstAsciiSlice("top")
    val bottom1 = ConstAsciiSlice("bottom1")
    val attr2 = ConstAsciiSlice("attr2")
    val number = ConstAsciiSlice("number")
    val middle = ConstAsciiSlice("middle")
    val name = ConstAsciiSlice("name")

    if (parser.parseNextTag) {
      if (parser.tag == top) assert(parseTop)
      else fail("unexpected top element")
    }

    def parseTop: Boolean = {
      println("parsing <top> element")
      while (parser.parseNextTag) {

        if (parser.isStartTag){
          parser.tag match {
            case `name` =>
              val cdata = parser.readCdataContent
              println(s"name CDATA: '$cdata'")
              assert(cdata == "Track 1")
            case `bottom1`  => assert(parseBottom1Attrs)
            case `number`  =>
              if (parser.parseContent && parser.getNextContentString) {
                val num = parser.contentString.toDouble
                println(s"number value = $num")
                assert (num == 1.234)
              } else fail("expected number content")
            case _ => // ignore
          }

        } else { // end tags
          if (parser.tag == middle) {
            println("terminating on </middle>")
            return true
          }
        }
      }
      false
    }

    def parseBottom1Attrs: Boolean = {
      while (parser.parseNextAttr) {
        parser.attrName match {
          case `attr2` =>
            println(s"bottom1 attr2 = '${parser.attrValue}'")
            return true
          case _ => // ignore
        }
      }
      false
    }
  }

  "a XmlPullParser2" should "extract flights and positions from SFDPS messages using Slice comparison" in {
    val msg = FileUtils.fileContentsAsBytes(baseResourceFile("sfdps-msg.xml")).get

    var nFlights = 0

    //--- tags, attrs and value extractors we need
    val flightIdentification = ConstAsciiSlice("flightIdentification")
    val aircraftIdentification = ConstAsciiSlice("aircraftIdentification")
    val flight = ConstAsciiSlice("flight")
    val positionTime = ConstAsciiSlice("positionTime") // attr
    val pos = ConstAsciiSlice("pos")
    val enRoute = ConstAsciiSlice("enRoute")
    val position = ConstAsciiSlice("position")
    val location = ConstAsciiSlice("location")
    val slicer = new SliceSplitter(' ')
    val valueSlice = MutAsciiSlice.empty

    val parser = new UTF8XmlPullParser2
    if (parser.initialize(msg)) {
      println(s"--- begin parsing ${msg.length} bytes")

      while (parser.parseNextTag) {
        if (parser.tag == flight && parser.isStartTag) parseFlight
      }
    }

    def parseFlight: Unit = {
      var id: String = null
      var lat,lon: Double = 0.0
      var dtg: String = null

      while (parser.parseNextTag) {
        val tag = parser.tag

        if (parser.isStartTag) {
          // note part of the == comparison can be inlined (as opposed to using match expressions, which defaults to equals)
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

        } else {
          if (tag == flight) {
            if (id != null && lat != 0.0 && lon != 0.0 && dtg != null) {
              nFlights += 1
              println(s"$nFlights: $id, $dtg, $lat, $lon")
              return
            }
          }
        }
      }
    }

    assert(nFlights == 100)
  }

  "a XmlPullParser2" should "extract flights and positions from SFDPS messages using StringMatchGenerator recognizers" in {
    val msg = FileUtils.fileContentsAsBytes(baseResourceFile("sfdps-msg.xml")).get
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

    if (parser.initialize(msg)) {
      println(s"--- begin parsing ${msg.length} bytes")

      while (parser.parseNextTag) {
        if (parser.tag == flight && parser.isStartTag) parseFlight
      }
    }
  }
}
