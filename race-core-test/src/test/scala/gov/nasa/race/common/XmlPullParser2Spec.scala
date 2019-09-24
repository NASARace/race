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
      |   <middle>
      |       <bottom1 attr1="123 < 321" attr2="whatdoiknow" attr3="skip this"/>
      |       <number> 1.234 <!-- in [fops/brizl] --> </number>
      |       <!-- this is ignored -->
      |       <bottom2> blah </bottom2>
      |   </middle>
      |</top>
      |""".stripMargin


  "a XmlPullParser2" should "print well-formed XML" in {
    val parser = new StringXmlPullParser2
    parser.initialize(testMsg)
    parser.printOn(System.out)
  }


  "a XmlPullParser2" should "extract known values from a test message" in {
    val parser = new StringXmlPullParser2
    parser.initialize(testMsg)

    //--- the parsed tags/attrs
    val top = Slice("top")
    val bottom1 = Slice("bottom1")
    val attr2 = Slice("attr2")
    val number = Slice("number")
    val middle = Slice("middle")

    if (parser.parseNextTag) {
      if (parser.tag == top) assert(parseTop)
      else fail("unexpected top element")
    }

    def parseTop: Boolean = {
      println("parsing <top> element")
      while (parser.parseNextTag) {

        if (parser.isStartTag){
          parser.tag match {
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

  "a XmlPullParser2" should "extract flights and positions from SFDPS messages" in {
    val msg = FileUtils.fileContentsAsBytes(baseResourceFile("sfdps-msg.xml")).get

    var nFlights = 0

    //--- tags, attrs and value extractors we need
    val flightIdentification = Slice("flightIdentification")
    val aircraftIdentification = Slice("aircraftIdentification")
    val flight = Slice("flight")
    val positionTime = Slice("positionTime") // attr
    val pos = Slice("pos")
    val enRoute = Slice("enRoute")
    val position = Slice("position")
    val location = Slice("location")
    val slicer = new SliceSplitter(' ')

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
        if (parser.isStartTag) {
          parser.tag match {
            case `flightIdentification` =>
              if (parser.parseAttr(aircraftIdentification)) id = parser.attrValue.intern

            case `position` =>
              if (parser.tagHasParent(enRoute)) {
                if (parser.parseAttr(positionTime)) dtg = parser.attrValue.toString
              }

            case `pos` =>
              if (parser.tagHasParent(location)) {
                if (parser.parseContent && parser.getNextContentString) {
                  slicer.setSource(parser.contentString)
                  if (slicer.hasNext) lat = slicer.next.toDouble
                  if (slicer.hasNext) lon = slicer.next.toDouble
                }
              }

            case _ => // ignore
          }
        } else {
          if (parser.tag == flight) {
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
}
