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
package gov.nasa.race.land

import gov.nasa.race.common.Sparse2DGrid
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Area.{SquareMeters, UndefinedArea}
import gov.nasa.race.uom.Power.{MegaWatt, UndefinedPower}
import gov.nasa.race.uom.{Area, DateTime, Power, Temperature}
import gov.nasa.race.uom.Temperature.{Kelvin, UndefinedTemperature}
import ucar.ma2.{Array => NcArray}
import ucar.nc2.dataset.NetcdfDatasets
import ucar.nc2.dt.grid.{GeoGrid, GridDataset}
import ucar.unidata.geoloc.LatLonPoint

import java.io.File
import scala.util.{Success, Failure, Try}

/**
  * spec for a Goes-R data product
  */
case class GoesRProduct (name: String, bucket: String, reader: Option[GoesRDataReader])

/**
  * data for Goes-R data product
  */
case class GoesRData(satellite: String, file: File, product: GoesRProduct, date: DateTime)

/**
  * some object that can read files that contain Goes-R data products
  */
trait GoesRDataReader {
  def read (data: GoesRData): Option[Any]
}

// we need mutability while we collect the data
class Pix (val x: Int, val y: Int,
           var center: GeoPosition = GeoPosition.undefinedPos, var bounds: Array[GeoPosition] = Array.empty,
           var temp: Temperature=UndefinedTemperature, var frp: Power=UndefinedPower, var area: Area=UndefinedArea,
           var dqf: Int= GoesRHotspot.DQF_UNKNOWN,
           var mask: Int= -1
          ) {
  def hasData: Boolean = (temp.isDefined || frp.isDefined || area.isDefined)

  def showBounds: Unit = {
    println(s"   NW: [${bounds(0).toGenericString2D}]")
    println(s"   NE: [${bounds(1).toGenericString2D}]")
    println(s"   SE: [${bounds(2).toGenericString2D}]")
    println(s"   SW: [${bounds(3).toGenericString2D}]")
  }

  override def toString: String = {
    f"[$x%4d,$y%4d]: ${center.toGenericString2D} temp=${temp.toKelvin}%6.2f, frp=${frp.toMegaWatt}%6.2f, area=${area.toSquareMeters}%6.0f, mask=$mask, dqf=$dqf"
  }

  def show: Unit = {
    println(this)
    //if (hasData) showBounds
  }
}

/**
  * reader for GoesR ABI L2 Fire (Hot Spot Characterization) data product ("ABI-L2-FDCC")
  *
  * see https://www.goes-r.gov/products/docs/PUG-L2+-vol5.pdf (pg 472) for details
  */
class AbiHotspotReader extends GoesRDataReader {

  val hs = new Sparse2DGrid[Pix]

  def read (data: GoesRData): Option[Any] = {
    if (data.product.name == "ABI-L2-FDCC") {
        val ncd = NetcdfDatasets.openDataset(data.file.getPath)
        val gds = new GridDataset(ncd)
        val date = DateTime.ofJ2000EpochSeconds( ncd.findVariable("t").readScalarLong)

        // note that nFires and nFrp do not seem to match valid fire pixels
        val nArea = ncd.findVariable("total_number_of_pixels_with_fire_area").readScalarInt
        val nTemp = ncd.findVariable("total_number_of_pixels_with_fire_temperature").readScalarInt
        val nFrp = ncd.findVariable("total_number_of_pixels_with_fire_radiative_power").readScalarInt
        val nFires = ncd.findVariable("total_number_of_pixels_with_fires_detected").readScalarInt

        if (nFires > 0) {
          getFirePixels(0, gds.findGridByName("Mask"), hs)

          if (hs.nonEmpty) {
            val tempGrid = gds.findGridByName("Temp")
            val powerGrid = gds.findGridByName("Power")
            val areaGrid = gds.findGridByName("Area")
            val dqfGrid = gds.findGridByName("DQF")

            // fill in the data values
            hs.foreach { pix =>
              val x = pix.x
              val y = pix.y
              pix.dqf = dqfGrid.readDataSlice( 0,0, y, x).reduce().getInt(0)
              pix.temp = Kelvin(tempGrid.readDataSlice( 0,0, y, x).reduce().getDouble(0))
              pix.frp  = MegaWatt(powerGrid.readDataSlice( 0,0, y, x).reduce().getDouble(0))
              pix.area = SquareMeters(areaGrid.readDataSlice( 0,0, y, x).reduce().getDouble(0))
            }
          }
        }

        if (hs.nonEmpty) {
          //hs.foreach(_.show)
          val res = hs.toArray.map( pix=> new GoesRHotspot( date, pix, data.satellite))
          hs.clear()

          if (res.nonEmpty) {
            Some(GoesRHotspots(date, data.satellite, res))
          } else None // no hotspots in fire pixels
        } else None // no fire pixels found
    } else None // not our product
  }

  // we populate the Pix map from good DQF values
  def getFirePixels (nMax: Int, grid: GeoGrid, hs: Sparse2DGrid[Pix]): Unit = {
    val maxY = grid.getDimension(0).getLength
    val maxX = grid.getDimension(1).getLength
    var n = 0

    for (y <- 0 until maxY) {
      val sl: NcArray = grid.readDataSlice(0,0,y,-1)
      val scanline: Array[Short] = sl.copyTo1DJavaArray.asInstanceOf[Array[Short]]
      for (x <- 0 until maxX) {
        val mask = scanline(x)
        if (GoesRHotspot.isValidFirePixel(mask)){
          val h = new Pix(x,y)
          h.mask = mask
          setCoordinates( x, y, grid, h)
          hs(x,y) = h

          n += 1
          if (nMax > 0 && n >= nMax) return // done
        }
      }
    }
  }

  def readData (nMax: Int, grid: GeoGrid, hs: Sparse2DGrid[Pix], op: (Pix,Float)=>Unit ): Unit = {
    val maxY = grid.getDimension(0).getLength
    val maxX = grid.getDimension(1).getLength
    var n = 0

    for (y <- 0 until maxY) {
      val sl: NcArray = grid.readDataSlice(0,0,y,-1)
      val scanline: Array[Float] = sl.copyTo1DJavaArray.asInstanceOf[Array[Float]]
      for (x <- 0 until maxX) {
        val v = scanline(x)
        if (!v.isNaN){
          val h = hs.getOrElseUpdate(x, y, new Pix(x,y))
          op(h,v)
          if (!h.center.isDefined) setCoordinates( x, y, grid, h)

          n += 1
          //if (n == nMax) return // done
        }
      }
    }
  }

  @inline final def gpos (lp: LatLonPoint): GeoPosition = GeoPosition.fromDegrees( lp.getLatitude, lp.getLongitude)

  def setCoordinates (x: Int, y: Int, grid: GeoGrid, h: Pix): Unit = {
    val gcs = grid.getCoordinateSystem
    val p = gcs.getLatLon(x,y)  // the datapoint center
    h.center = gpos( p)

    // given the angular resolution we just do a cartesian approximation of the bounds. Note this is not
    // accurate close to the satellite horizon

    val rx = if (x > 0) {  // neighboring x data point
      val px = gcs.getLatLon(x-1,y)
      ((p.getLatitude - px.getLatitude)/2, (p.getLongitude - px.getLongitude)/2)
    } else {
      val px = gcs.getLatLon(x+1,y)
      ((px.getLatitude - p.getLatitude)/2, (px.getLongitude - p.getLongitude)/2)
    }

    val ry = if (y > 0) {  // neighboring y data point
      val py = gcs.getLatLon(x,y-1)
      ((p.getLatitude - py.getLatitude)/2, (p.getLongitude - py.getLongitude)/2)
    } else {
      val py = gcs.getLatLon(x,y+1)
      ((py.getLatitude - p.getLatitude)/2, (py.getLongitude - p.getLongitude)/2)
    }

    val pNW = GeoPosition.fromDegrees( p.getLatitude - rx._1 - ry._1, p.getLongitude - rx._2 - ry._2 )
    val pNE = GeoPosition.fromDegrees( p.getLatitude + rx._1 - ry._1, p.getLongitude + rx._2 - ry._2 )
    val pSE = GeoPosition.fromDegrees( p.getLatitude + rx._1 + ry._1, p.getLongitude + rx._2 + ry._2 )
    val pSW = GeoPosition.fromDegrees( p.getLatitude - rx._1 + ry._1, p.getLongitude - rx._2 + ry._2 )
    h.bounds = Array(pNW,pNE,pSE,pSW)
  }
}
