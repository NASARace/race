/*
 * Copyright (c) 2016, United States Government, as represented by the 
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

package gov.nasa.race.data.translators

import java.awt.image.{IndexColorModel, DataBuffer, BufferedImage}
import com.typesafe.config.Config
import gov.nasa.race.common.{Translator, ConfigurableTranslator, XmlPullParser}
import gov.nasa.race.data.{PrecipImageStore, LatLonPos, PrecipImage}
import org.joda.time.DateTime
import squants.Length
import squants.space.{Angle, Meters, Degrees}

/*
 * translates ITWS precip messages into PrecipData objects
 * 
 */

object ITWSprecip2PrecipImage {
  val cmap = Array[Int](  // reference color model
    0xffffffff, // 0: no precipitation - transparent colormodel index
    0xffa0f000, // 1: level 1
    0xff60b000, // 2: level 2
    0xfff0f000, // 3: level 3
    0xfff0c000, // 4: level 4
    0xffe09000, // 5: level 5
    0xffa00000, // 6: level 6
    0x00000000, // 7: attenuated
    0x00000000, // 8: anomalous propagation
    0x00000000, // 9: bad value
    0x00000000, // 10: ?
    0x00000000, // 11: ?
    0x00000000, // 12: ?
    0x00000000, // 13: ? no coverage ("should not be present")
    0x00000000, // 14: ? - " -
    0x00000000  // 15: no coverage
  )

  // we make it a var so that we can set it from tests
  var colorModel =  new IndexColorModel( 8, 16, cmap, 0, true, 0, DataBuffer.TYPE_BYTE)


}
import ITWSprecip2PrecipImage._


/**
 * translate ITWS precip messages into PrecipData objects
 *
 * Note - unfortunately the maxPrecipLevel comes *after* the data, i.e. we cannot
 * upfront detect if we should parse at all
 */
class ITWSprecip2PrecipImage (val config: Config=null) extends XmlPullParser
                            with RLEByteImageParser with ConfigurableTranslator {
  private var scanLine = new Array[Byte](2056)
  def getScanLine (w: Int) = {
    if (scanLine.length < w) scanLine = new Array[Byte](w)
    scanLine
  }

  def translate (src: Any): Option[PrecipImage] = {
    src match {
      case xml: String if xml.nonEmpty => translateText(xml)
      case Some(xml:String) if xml.nonEmpty => translateText(xml)
      case other => None // nothing else supported yet
    }
  }

  def translateText (s: String): Option[PrecipImage] = {
    initialize(s.toCharArray)

    // we deliberately use null here to fail early
    var product = 0 // 9849: precip 5nm, 9850: tracon, 9905: long range
    var itwsSite: String = null // site id (e.g. N90 - describing a list of airports/tracons)
    var genDate: DateTime = null; var expDate: DateTime = null;
    var trpLat: Angle=null; var trpLon: Angle=null; var rotation: Angle=null// degrees
    var xoffset: Length=null; var yoffset: Length=null; // meters
    var dx: Length=null; var dy: Length=null; var dz: Length=null; // meters
    var nRows = -1; var nCols = -1;
    var maxPrecipLevel = 0; // 0-6 - no use to display/parse 0
    var img: BufferedImage = null
    var id: String = null

    try {
      while (parseNextElement()) {
        if (isStartElement) {
          tag match {
            case "product_msg_id" => product = readInt()
            case "product_header_itws_sites" => itwsSite = readText()
            case "product_header_generation_time_seconds" => genDate = new DateTime( 1000L * readInt())
            case "product_header_expiration_time_seconds" => expDate = new DateTime( 1000L * readInt())

            case "prcp_TRP_latitude" => trpLat = readDegreesWithPrecision
            case "prcp_TRP_longitude" => trpLon = readDegreesWithPrecision
            case "prcp_rotation" => rotation = readDegreesWithPrecision

            case "prcp_xoffset" => xoffset = readMetersWithUnit
            case "prcp_yoffset" => yoffset = readMetersWithUnit

            case "prcp_dx" => dx = readMetersWithUnit
            case "prcp_dy" => dy = readMetersWithUnit

            // image data is padded to be square - we don't crop this during RLE expansion (speed vs. little space)
            //case "prcp_nrows" => nRows = readInt
            //case "prcp_ncols" => nCols = readInt
            case "prcp_grid_max_y" => nRows = readInt
            case "prcp_grid_max_x" => nCols = readInt

            case "prcp_grid_compressed" =>
              id = PrecipImageStore.computeId(product,itwsSite,xoffset,yoffset)
              img = PrecipImageStore.imageStore.getOrElseUpdate(id, createBufferedImage(nCols, nRows, colorModel))
              readImage(getScanLine(nCols),img)

            case "prcp_grid_max_precip_level" => maxPrecipLevel = readInt()

            case other => // ignore
          }
        }
      }

      // <2do> do some sanity checks here
      Some( PrecipImage( id, product, itwsSite,
                         genDate, expDate,
                         LatLonPos(trpLat,trpLon), xoffset, yoffset, rotation,
                         dx * nCols, dy * nRows,
                         maxPrecipLevel, img))
    } catch {
      case t: Throwable =>
        //t.printStackTrace();
        println(t)
        None
    }
  }

  def readDegreesWithPrecision: Angle = Degrees(readDoubleAttribute("precision") * readDouble())

  def readMetersWithUnit: Length = {
    val unit = readAttribute("unit")
    val n = readInt()
    unit match {
      case "meters" => Meters(n)
      // otherwise match error for now
    }
  }

  def createBufferedImage (width: Int, height: Int, cm: IndexColorModel) = {
    new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, cm)
  }
}

