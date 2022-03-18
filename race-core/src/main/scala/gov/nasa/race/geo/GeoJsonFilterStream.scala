/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.geo

import gov.nasa.race.common.ConstUtf8Slice.utf8
import gov.nasa.race.common.MutUtf8Slice
import gov.nasa.race.uom.Angle.Degrees
import gov.nasa.race.uom.Length.Meters
import gov.nasa.race.uom.{Angle, Length}

import java.io.InputStream

object GeoJsonFilterStream {
  val EOS: Int = -1

  //--- lexical constants

  val TYPE = utf8("type")
  val FEATURES = utf8("features")
  val GEOMETRIES = utf8("geometries")
  val GEOMETRY = utf8("geometry")
  val BBOX = utf8("bbox")
  val COORDINATES = utf8("coordinates")
  val PROPERTIES = utf8("properties")

  // GeoJSON types
  val FEATURECOLLECTION = utf8("FeatureCollection")
  val GEOMETRYCOLLECTION = utf8("GeometryCollection")
  val FEATURE = utf8("Feature")
  val POINT = utf8("Point")
  val LINESTRING = utf8("LineString")
  val POLYGON = utf8("Polygon")
  val MULTIPOINT = utf8("MultiPoint")
  val MULTILINESTRING = utf8("MultiLineString")
  val MULTIPOLYGON = utf8("MultiPolygon")
}
import gov.nasa.race.geo.GeoJsonFilterStream._

/**
  * an InputStream filter for GeoJSON streams as defined by RFC 7946, implemented as a stream decorator
  *
  * This processes GeoJSON streams as defined in RFC 7946, filtering all 'Geometry' or 'Feature' objects that do not pass
  * a filter function with respect to their associated 'coordinates' array(s). Any coordinate point that passes
  * the filter function leads to inclusion of the object
  *
  * The motivation behind this class is to provide on-the-fly filtering for large geoJSON streams (potentially from files
  * too big to fit into client memory) within constant space, and without the need to do full JSON parsing.
  *
  *
  * GeoJsonObject := Geometry | Feature | FeatureCollection
  *
  * FeatureCollection := '{' "type:" "FeatureCollection" ','
  *                          ["bbox:" '[' BoxCoords ']' ',' ]
  *                          "features:" '[' Feature... ']'
  *                      '}'
  *
  * Feature := '{' "type:" "Feature" ','
  *                 ["bbox:" '[' BoxCoords ']' ',' ]
  *                 "geometry:" Geometry ','
  *                "properties:" JsonObject
  *            '}'
  *
  * Geometry := SimpleGeometry | GeometryCollection
  *
  * GeometryCollection := '{' "type:" "GeometryCollection" ','
  *                           ["bbox:" '[' BoxCoords ']' ',' ]
  *                           "geometries:" '[' SimpleGeometry... ']'
  *                       '}'
  *
  * SimpleGeometry := '{' "type:"
  *                          "Point" | "LineString" | "Polygon" | "MultiPoint" | "MultiLineString" | "MultiPolygon" ','
  *                       ["bbox:" '[' BoxCoords ']' ',' ]
  *                       "coordinates:" Coords
  *                   '}'
  */
class GeoJsonFilterStream(val is: InputStream, filter: GeoPosition=>Boolean, bufferSize: Int=4096) extends InputStream {
  var buf = new Array[Byte](bufferSize)
  val tok = new MutUtf8Slice(buf,0,0)
  val position = new MutLatLonPos()

  //--- input state
  var limit = 0
  var pos = 0
  var eos = false
  var pushBack: Int = -1

  //--- output state
  var outPos = 0

  def checkBufSize(): Unit = {
    if (pos == buf.length) {
      val newBuf = new Array[Byte](buf.length*2)
      System.arraycopy(buf,0,newBuf,0,pos)
      buf = newBuf
      tok.data = buf
    }
  }

  @inline def readInput(): Int = {
    if (pushBack != -1){
      val b = pushBack
      pushBack = -1
      b
    } else {
      val b = is.read()
      if (b == EOS) eos = true
      b
    }
  }

  @inline def copyWithoutRead (b: Int): Unit = {
    checkBufSize()
    buf(pos) = b.toByte
    pos += 1
  }

  @inline def copyAndRead (b: Int): Int = {
    copyWithoutRead(b)
    readInput()
  }

  // positioned on opening '"', copy to closing '"' (inclusive)
  @inline def copyToEndOfString(): Unit = {
    var b: Int = readInput()
    while (b != EOS && b != '"') {
      b = copyAndRead(b)
    }
    copyWithoutRead(b)
  }

  @inline def readNextColon(): Unit = {
    val b: Int = readInput()
    if (b != ':') parseError("expected ':'")
    copyWithoutRead(b)
  }

  def readGeoObject(): Boolean = {
    var objStart = 0
    var lvl = 0
    var geoStart = 0
    var geoLvl = 0
    var inFeature = false

    if (!eos) {
      var b: Int = readInput()
      while (b != EOS) {
        val c = b.toChar
        copyWithoutRead(b)

        b match {
          case '{' =>
            lvl += 1
            objStart = pos-1  // we don't know yet if it is a geoJSON object start

          case '}' =>
            lvl -= 1

          case '"' =>
            tok.off = pos
            copyToEndOfString()
            tok.len = pos - tok.off-1

            tok match {
              case FEATURE =>
                inFeature = true
                geoStart = objStart // this is the buffer pos to revert to if this object is filtered
                geoLvl = lvl // this is the level we skip to once we have coordinates analyzed

              case POINT | LINESTRING | POLYGON | MULTIPOINT | MULTILINESTRING | MULTIPOLYGON =>
                if (!inFeature) {
                  geoStart = objStart // this is the buffer pos to revert to if this object is filtered
                  geoLvl = lvl // this is the level we skip to once we have coordinates analyzed
                } else {
                  inFeature = false // reset
                }

              case COORDINATES =>
                readNextColon()
                val pass = checkCoordinates()
                readToGeoEnd(pass)
                if (!pass) pos = geoStart
                if (readToNextObjectStart(pass) != EOS){
                  objStart = pos-1
                }
                if (pass) return true
                lvl = 0

              case _ => // nothing, just copy
            }

          case _ => // nothing special, we just copy to the buffer
        }

        b = readInput()
      }

      pos > 0 // do we have unread bytes
    } else false // already at eos
  }

  def readToNextObjectStart (copy: Boolean): Int = {
    var b = readInput()
    while (b != EOS && b != '{') {
      b = if (copy) copyAndRead(b) else readInput()
    }
    if (b != EOS) {
      pushBack = b
    }
    b
  }

  def parseError (msg: String): Unit = {
    throw new RuntimeException(msg)
  }

  @inline def isNumberStart(b: Int): Boolean = {
    b == '-' || b == '.' || (b >= '0' && b <= '9')
  }

  @inline def isWhiteSpace(b: Int): Boolean = {
    b == ' ' || b == 9 || b == 10 || b == 13
  }

  @inline def readNumber(b0: Int): Int = {
    var b: Int = b0

    tok.off = pos
    // read to number end
    while (b != EOS && isNumberStart(b)) b = copyAndRead(b)
    tok.len = pos - tok.off

    b // either separator char ',' | ']' or whitespace
  }

  // we are positioned on opening '[' and read past closing ']'.
  // Note that we still have to copy to buf since we don't know yet if object will be filtered
  // as soon as there is one inside coordinate we can shortcut to the closing ']'
  // note also this does not change the object level of our caller
  def checkCoordinates(): Boolean = {
    var b: Int = readInput()
    var lvl = 0
    var ignoreObject = true

    while (ignoreObject) {
      var lat: Angle = Angle.Angle0
      var lon: Angle = Angle.Angle0
      var alt: Length = Length.Length0

      while (b != EOS && !isNumberStart(b) ) { // count array nesting
        if (b == '[') lvl += 1
        else if (!isWhiteSpace(b)) parseError("illegal start of coordinate point")
        b = copyAndRead(b)
      }

      b = readNumber(b)
      lon = Degrees(tok.toDouble)
      while (b != EOS && !isNumberStart(b) ) {  // this has to be followed by a lat number
        if (!isWhiteSpace(b) && b != ',') parseError("expected ',' before lat number")
        b = copyAndRead(b)
      }

      b = readNumber(b)
      lat = Degrees(tok.toDouble)
      while (b != EOS && isWhiteSpace(b)) b = copyAndRead(b) // skip over sep trailing whitespace
      if (b == ',') { // 3d coordinates, read alt
        while (b != EOS && isWhiteSpace(b)) b = copyAndRead(b)
        b = readNumber(b)
        alt = Meters(tok.toDouble)
      }

      position.update(lat,lon,alt)
      ignoreObject = !filter(position)

      while (b != EOS && (isWhiteSpace(b) || b == ']')){
        if (b == ']') lvl -= 1
        b = copyAndRead(b)
      }
      if (lvl == 0) {  // done, end of "[...]" coordinate value reached
        pushBack = b
        return !ignoreObject

      } else { // more coordinate points to follow
        if (b != ',') parseError("expected ',' before next coordinate point")
        b = copyAndRead(b)
      }
    }

    if (lvl > 0) { // shortcut - we found an inside point before the coordinate section end
      while (b != EOS) {
        if (b == ']') {
          lvl -= 1
          if (lvl == 0) {
            copyWithoutRead(b)
            return true
          }
        } else if (b == '[') {
          lvl += 1
        } // else we don't care and just copy
        b = copyAndRead(b)
      }
      throw new RuntimeException("internal error, should never get here")

    } else {
      parseError("unbalanced coordinate section")
      throw new RuntimeException("internal error, should never get here")
    }
  }

  // this is at the end ']' of a "coordinates" value so we iterate until we are two levels up
  def readToGeoEnd(isAdded: Boolean): Unit = {
    var lvl = 2
    var b = readInput()
    while (b != EOS) {
      val c = b.toChar
      if (isAdded) copyWithoutRead(b)

      b match { // TODO - fix '{' in quoted values
        case '{' =>
          lvl += 1
        case '}' =>
          lvl -= 1
          if (lvl ==0) return
        case _ => // nothing to process
      }

      b = is.read()
    }
  }

  override def read(): Int = {
    if (outPos >= pos) {
      pos = 0
      outPos = 0
      readGeoObject()
    }

    if (outPos < pos) { // we have unread data
      val b = buf(outPos)
      outPos += 1
      b
    } else -1
  }
}
