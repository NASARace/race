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
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for XmlPullParser2
  */
class XmlPullParser2Spec extends AnyFlatSpec with RaceSpec {

  val testMsg =
    """
      |<top>
      |   <middle>
      |       <bottom1 attr1="123" attr2="whatdoiknow" attr3="skip this"/>
      |       <number>1.234</number>
      |       <bottom2>blah</bottom2>
      |   </middle>
      |</top>
      |""".stripMargin

  def indent (level: Int): Unit = {
    for (i <- 0 until level) print("  ")
  }

  "a XmlPullParser2" should "print well-formed XML" in {
    val parser = new BufferedXmlPullParser2
    parser.initialize(testMsg)
    parser.printOn(System.out)
  }

  "a XmlPullParser2" should "extract known values from a test message" in {
    val parser = new BufferedXmlPullParser2
    parser.initialize(testMsg)

    val Top = new HashedSliceImpl("top")
    val Bottom1 = new HashedSliceImpl("bottom1")
    val Attr2 = new HashedSliceImpl("attr2")
    val Attr3 = new HashedSliceImpl("attr3")
    val Number = new HashedSliceImpl("number")

    while (parser.parseNextElement) {
      parser.element match {
        case Top =>
          if (parser.isStartElement) println(s"top start") else println("top end")

        case Bottom1 =>
          if (parser.isStartElement) {
            while (parser.parseNextAttribute) {
              parser.attr match {
                case Attr2 =>
                  println(s"bottom1 attr2 = '${parser.attrValue}'")
                  //parser.skipAttributes
                case Attr3 => println("ARGHH attr3")
                case _ => // ignore
              }
            }
          }

        case Number =>
          if (parser.isStartElement) {
            parser.parseText
            println(s"number = ${parser.elementText.toDouble}")
          }

        case _ => // ignore
      }
    }
  }
}
