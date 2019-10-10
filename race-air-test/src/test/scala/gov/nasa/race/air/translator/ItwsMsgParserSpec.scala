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
package gov.nasa.race.air.translator

import java.awt.image.{DataBuffer, IndexColorModel}

import gov.nasa.race.air.PrecipImage
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.util.FileUtils.fileContentsAsUTF8String
import javax.imageio.ImageIO
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for ItwsMsgParser
  */
class ItwsMsgParserSpec extends AnyFlatSpec with RaceSpec {

  // create a color model we can compare with the reference ITWSGriddedConverter from Lincoln Labs
  def createColorModel = {
    val cmap = Array[Int](  // reference color model
      0x00000000, // 0: no precipitation - transparent
      0xffa0f000, // 1: level 1
      0xff60b000, // 2: level 2
      0xfff0f000, // 3: level 3
      0xfff0c000, // 4: level 4
      0xffe09000, // 5: level 5
      0xffa00000, // 6: level 6
      0xff1c1c1c, // 7: attenuated
      0xff1f1f1f, // 8: anomalous propagation
      0xff1f1f1f, // 9: bad value
      0xff8ec870, // 10: ?
      0xff78a85c, // 11: ?
      0xff208220, // 12: ?
      0xff4b4b4b, // 13: ? no coverage ("should not be present")
      0xff4b4b4b, // 14: ? - " -
      0xff4b4b4b  // 15: no coverage.
    )

    new IndexColorModel( 8, 16, cmap, 0, false, 0, DataBuffer.TYPE_BYTE)
  }

  mkTestOutputDir

  ItwsMsgParser.colorModel = createColorModel
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("itws-precip.xml"))

  "a ItwsMsgParser" should "reproduce known image data" in {
    val translator = new ItwsMsgParser
    val res = translator.translate(xmlMsg)
    println(s"result: $res")

    res match {
      case Some(pi:PrecipImage) =>
        val img = pi.img
        val raster = img.getData
        ImageIO.write(img, "PNG", testOutputFile("itws-precip.png"))

        val imgRef = ImageIO.read(baseResourceFile("itws-precip.png"))
        val rasterRef = imgRef.getData

        val w = raster.getWidth
        val h = raster.getHeight
        println(s"..checking dimensions $w,$h")
        raster.getBounds shouldBe( rasterRef.getBounds)

        println(s"..checking data elements")
        raster.getDataElements(0,0,w,h,null) shouldBe( rasterRef.getDataElements(0,0,w,h,null))

      case other => fail(s"wrong result object: $other")
    }
  }

  "a ItwsMsgParser" should "parse a well formed itwsMsg" in {
    val xmlMsg = fileContentsAsUTF8String(baseResourceFile("itwsMsg.xml"))
    val translator = new ItwsMsgParser
    val res = translator.translate(xmlMsg)
    println(s"result: $res")
  }
}
