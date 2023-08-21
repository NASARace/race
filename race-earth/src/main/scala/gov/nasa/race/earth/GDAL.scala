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

package gov.nasa.race.earth

import java.io.File
import gov.nasa.race.common.ExternalProc
import gov.nasa.race.util.{FileUtils, StringUtils}

object GdalCommand {
  val driverFor: Map[String,String] = Map(
    ("csv" -> "CSV"),
    ("json" -> "GeoJSON"),
    ("geojson" -> "GeoJSON"),
    ("nljson" -> "GeoJSONSeq"),
    // ... and a gazillion more
  )
}

/**
 * common root for ExternalProcs that run GDAL executables
 * they all require an input file
 *
 * TODO - still need to make in/out driver args consistent
 */
trait GdalCommand[T] extends ExternalProc[T] {
  protected var inFile: Option[File] = None

  def setInFile (f: File): this.type = {
    if (f.exists()) {
      inFile = Some(f)
      this
    } else throw new RuntimeException(s"gdal input file does not exist: $f")
  }

  override def canRun: Boolean = inFile.isDefined && super.canRun

  // we don't reset the inFile
}

/**
 * a GdalCommand that creates a file
 */
trait GdalFileCreatorCommand extends GdalCommand[File] {
  protected var outFile: Option[File] = None
  protected var outDriver: Option[String] = None

  override def canRun: Boolean = outFile.isDefined && super.canRun

  def setOutDriver (spec: String): this.type = {
    outDriver = Some(spec) // TODO - we should check against supported GDAL raster/vector drivers here
    this
  }

  def setOutFile (f: File): this.type = {
    FileUtils.ensureWritable(f) match {
      case of@Some(_) =>
        outFile = if (FileUtils.isGzipped(f)) { // we have to set -f since most gdal execs would choke on unknown type
          GdalCommand.driverFor.get( FileUtils.getGzippedExtension(f).get) match {
            case Some(driverName) =>
              setOutDriver(driverName)
              Some(new File(f.getParentFile, s"/vsigzip/${f.getName}"))

            case _ => of // let's hope GDAL handles it
          }
        } else of
        this
      case None => throw new RuntimeException(s"gdal output file cannot be written: $f")
    }
  }

  override def buildCommand: StringBuilder = {
    super.buildCommand
      .append(s" ${inFile.get.getPath}")
      .append(s" ${outFile.get.getPath}")
  }

  override protected def getSuccessValue: File = {
    if (outFile.isDefined) {
      if (outFile.get.isFile) outFile.get
      else throw new RuntimeException(s"outFile not found: ${outFile.get}")
    } else throw new RuntimeException("no outFile set")
  }
}

class GdalInfo (val prog: File, val inFile: File) extends ExternalProc[File]  {
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!inFile.isFile) throw new RuntimeException(s"input file not found: $inFile")

  protected override def buildCommand: StringBuilder = super.buildCommand.append(s" $inFile")

  override def getSuccessValue: File = {
    if (!inFile.isFile) throw new RuntimeException(s"output file not found: $inFile")
    inFile
  }

  def getJson(): this.type = addArg("-json")
  def getMinMax(): this.type = addArg("-mm")
  def getStats(): this.type = addArg( "-stats")
  def getStatsApprox(): this.type = addArg( "-approx_stats")
  def getHistogram(): this.type = addArg( "-hist")
  def setNoGroundControlPoints(): this.type = addArg( "-nogcp")
  def setNoMetadata(): this.type = addArg( "-nomd")
  def setNoRasterPrint(): this.type = addArg( "-norat")
  def setNoColorPrint(): this.type = addArg( "-noct")
  def setCheckSum(): this.type = addArg( "-checksum")
  def getMetadataList(): this.type = addArg( "-listmdd")
  def setMetadata(domain: String=""): this.type = addArg( s"-mdd $domain")
  def getFirstFile(): this.type = addArg( "-nofl")
  def setWKTFormat(wkt: String): this.type = {
    // TODO - can only be wkt1, wkt2, wkt2_205, wtk2_2018
    addArg(s"-wkt_format $wkt")
  }
  def getDataset(n: String): this.type = addArg( s"-sd $n")
  def getProjection(): this.type = addArg( "-proj4")
  def setDatasetOpenOption(option: String): this.type = addArg( s"-oo $option")
  def setDriver(format: String): this.type = addArg( s"-if $format")
}

/**
 * a generic gdalwarp wrapper
 * TODO - still needs to handle checks for repetitive setters etc.
 */
class GdalWarp (val prog: File) extends GdalFileCreatorCommand {
  
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")

  def setWarpBand(bandN: String): this.type = addArg( s"-b $bandN") // version 3.7 uses srcband, dstband
  def setTargetSrs (srsName: String): this.type = addArg( s"-t_srs $srsName")
  def setSourceSrs (srsName: String): this.type = addArg( s"-s_srs $srsName")
  def setSourceEpoch(epoch: String): this.type = addArg( s"-s_coord_epoch $epoch")
  def setTargetEpoch(epoch: String): this.type = addArg( s"-t_coord_epoch $epoch")
  def setTargetBounds(xMin:String, yMin:String, xMax:String, yMax:String): this.type = addArg( s"-te $xMin $yMin $xMax $yMax")
  def setTargetBoundSrs (srsName: String): this.type = addArg( s"-te_srs $srsName")
  def setCT(projName: String): this.type = addArg( s"-CT $projName")
  def setTransformerOption(transformerName: String): this.type = addArg( s"-to $transformerName")
  def useVerticalShift(): this.type = addArg( "-vshift")
  def disableVerticalShift(): this.type = addArg( "-novshift")
  def setPolynomialOrder(pOrder: String): this.type = addArg( s"-order $pOrder") // must be between 1-3
  def useSpline(): this.type = addArg( "-tps")
  def useRPC(): this.type = addArg( "-rpc")
  def useGeolocationArrays(): this.type = addArg( "-geoloc")
  def setErrorThreshold(eThresh: String): this.type = addArg( s"-et $eThresh")
  def refineGCPS(tolerance: String, minGCPS: String):this.type = addArg( s"-refine_gcps $tolerance $minGCPS")
  def setTargetResolution(xRes:String = "", yRes:String = "", square:String = ""): this.type = addArg( s"-tr $xRes $yRes $square")
  def targetAlignedPixels(): this.type = addArg( "-tap")
  def setTargetSize(width:String, height:String): this.type = addArg( s"-ts $width $height")

  def setOverviewLevel(level: String = ""): this.type = {
    //level is optional, uses AUTO by default, can input AUTO-n or NONE as well
    addArg(s"-ovr $level")
  }

  def setWarpOption(warpOption: String): this.type = addArg( s"-wo $warpOption")
  def setTargetBandType(bandType: String): this.type = addArg( s"-ot $bandType")
  def setWorkingPixelType(pixelType: String): this.type = addArg( s"-wt $pixelType")
  def setResamplingMethod(method: String): this.type = addArg( s"-r $method")
  def setSourceNoDataValue(value: String): this.type = addArg( s"-srcnodata $value")
  def setSourceNoDataValues(values: Seq[String]): this.type = addArg( s"-srcnodata ${StringUtils.mkSepString(values, ' ')}")
  def setTargetNoDataValue(value: String): this.type = addArg( s"-dstnodata $value")
  def setTargetNoDataValues(values: Seq[String]): this.type = addArg( s"-dstnodata ${StringUtils.mkSepString(values, ' ')}")
  def setLastBandAlpha(): this.type = addArg( "-srcalpha")
  def setLastBandNotAlpha(): this.type = addArg( "-nosrcalpha")
  def setTargetAlpha(): this.type = addArg( "-dstalpha")
  def setMemorySize(memory: String): this.type = addArg( s"-wm $memory")
  def useMultithread(): this.type = addArg( "-multi")
  def beQuiet(): this.type = addArg( "-q")
  override def setOutDriver(format: String): this.type = addArg( s"-of $format")
  def setCreationOptions(option: String): this.type = addArg( s"-co $option")
  def setCutline(dataSource: String): this.type = addArg( s"-cutline $dataSource")
  def setCutlineLayer(layerName: String): this.type = addArg( s"-cl $layerName")
  def setCutlineRestriction(expression: String): this.type = addArg( s"-cwhere $expression")
  def setCutlineSQL(query: String): this.type = addArg( s"-csql $query")
  def setCutlineBlend(distance: String): this.type = addArg( s"-cblend $distance")
  def setCropToCutline(): this.type = addArg( "-crop_to_cutline")
  def setOverwrite(): this.type = addArg( "-overwrite")
  def setNoCopyMetadata(): this.type = addArg( "-nomd")
  def setMetadataConflicts(conflictValue: String): this.type = addArg( s"-cvmd $conflictValue")
  def setColorInterpretation(): this.type = addArg( "-setci")
  def setSourceOpenOption(option: String): this.type = addArg( s"-oo $option")
  def setDestinationOpenOption(option: String): this.type = addArg( s"-doo $option")
}

class GdalTranslate (val prog: File, val inFile: File, val outFile: File) extends ExternalProc[File] {
  
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!inFile.isFile) throw new RuntimeException(s"input file not found: $inFile")

  protected override def buildCommand: StringBuilder = super.buildCommand.append(" $inFile $outFile")
  
  override def getSuccessValue: File = {
    if (!outFile.isFile) throw new RuntimeException(s"output file not found: $outFile")
    outFile
  }

  def setOutputType(outputType: String): this.type = {
    // TODO: can only be: Byte, Int8, UInt16, Int16, UInt32, Int32, UInt64, Int64, Float32, Float64, CInt16, CInt32, CFloat32 or CFloat64
    addArg(s"-ot $outputType")
  }

  def setStrict(): this.type = addArg( "=strict")
  def setInputDriver(format: String): this.type = addArg( s"-if $format")
  def setOutputDriver(format: String): this.type = addArg( s"-of $format")
  def setBand(bandN: String): this.type = addArg( s"-b $bandN")
  def setMask(bandN: String): this.type = addArg( s"-mask $bandN")

  def setExpandColorTable(colorTable: String): this.type = {
    // can be gray, rgb, or rgba
    addArg(s"-expand $colorTable")
  }

  def setOutputSize(xSize: String = "0", ySize: String = "0"): this.type = {
    // can be gray, rgb, or rgba
    addArg(s"-outsize $xSize $ySize")
  }

  def setTargetResolution(xRes:String, yRes:String): this.type = addArg( s"-tr $xRes $yRes")

  def setOverviewLevel(level: String = ""): this.type = {
    //level is optional, uses AUTO by default, can input AUTO-n or NONE as well
    addArg(s"-ovr $level")
  }

  def setResamplingMethod(method: String): this.type = addArg( s"-r $method")

  def setScale(src_min: String = "", src_max: String = "", dst_min: String = "", dst_max: String = ""): this.type = {
    addArg(s"-scale $src_min $src_max $dst_min $dst_max")
  }

  def setScaleExponent(exp: Int): this.type = {
    if (!args.contains("-scale")) throw new RuntimeException(s"Scaling exponent can only be used following setScale()")
    addArg(s"-exponent $exp")
  }

  def setUnscale(): this.type = addArg( "-unscale")
  def setWindowPixels(xOff: String, yOff: String, xSize: String, ySize: String): this.type = addArg( s"-srcwin $xOff $yOff $xSize $ySize")
  def setWindowCoords(ulx: String, uly: String, lrx: String, lry: String): this.type = addArg( s"-projwin $ulx $uly $lrx $lry")
  def setWindowSrs(srsName: String): this.type = addArg( s"-projwin_srs $srsName")
  def setPartiallyOutsideError(): this.type = addArg( "-epo")
  def setCompletelyOutsideError(): this.type = addArg( "-eco")
  def setCoordEpoch(epoch: String): this.type = addArg( s"-a_coord_epoch $epoch")
  def setBandScale(value: String): this.type = addArg( s"-a_scale $value")
  def setBandOffset(value: String): this.type = addArg( s"-a_offset $value")
  def setOutputBounds(ulx: String, uly: String, lrx: String, lry: String): this.type = addArg( s"-a_ullr $ulx $uly $lrx $lry")
  def setNoDataValue(value: String): this.type = addArg( s"-a_nodata $value")
  def setBandColorInterpretation(bandN: String, color: String): this.type = addArg( s"-colorinterp_$bandN $color")
  def setColorInterpretation(colors: Seq[String]): this.type = addArg( s"""-colorinterp ${colors.mkString(",")}""")
  def setMetaData(key: String, value: String): this.type = addArg( s"-mo $key=$value")
  def setCreationOptions(option: String): this.type = addArg( s"-co $option")
  def setNoGroundControlPoints(): this.type = addArg( "-nogcp")

  def setGroundControlPoints(pixel: String, line: String, easting: String, northing: String, elevation: String): this.type = {
    addArg(s"-gcp $pixel $line $easting $elevation")
  }

  def beQuiet(): this.type = addArg( "-q")
  def setCopyDatasets(): this.type = addArg( "-sds")
  def getStats(): this.type = addArg( "-stats")
  def setNoRAT(): this.type = addArg( "-norat")
  def setNoXMP(): this.type = addArg( "-noxmp")
  def setDatasetOpenOption(option: String): this.type = addArg( s"-oo $option")
}


class GdalContour (val prog: File) extends GdalFileCreatorCommand {
  def setBand(bandNo:Int): this.type = addArg( s"-b $bandNo")
  def setInterval(interval: Double): this.type = addArg( s"-i $interval")
  def setAttrName (name: String): this.type = addArg( s"-a $name")
  def setAttrMinName (name: String): this.type = addArg( s"-amin $name")
  def setAttrMaxName (name: String): this.type = addArg(s"-amax $name")
  def setPolygon(): this.type = addArg("-p")
  def setExponent(base: Int): this.type = addArg( s"-e $base")
}

class GdalPolygonize (val prog: File, val pythonExe: Option[File]) extends GdalFileCreatorCommand {
  var outField: Option[String] = None
  var outLayer: Option[String] = None
  //var band: Option[Int] = None
  def setBand(bandNo:Int) : this.type = addArg( s"-b $bandNo")
  def beQuiet(): this.type = addArg( "-q")
  def setConnectedness(): this.type = addArg("-8")
  def setPolygonOptions(key: String, value: String): this.type = addArg( s"-o $key=$value")
  def setOverwrite(): this.type = addArg("-overwrite")
  def setOutputLayer(layer: String) = {
    outLayer = Some(layer)
    this
  }//needs to go after outfile
  def setOutputField(field: String)  = {
    outField = Some(field)
    this
  }//needs to go after outfile
  override def buildCommand: StringBuilder = {
    args.foldRight(new StringBuilder(pythonExe.get.getPath + " " + prog.getPath))( (a,sb) => sb.append(' ').append(a))
      .append(s" ${inFile.get.getPath}")
      .append(s" ${outFile.get.getPath}")
      .append(s" ${outLayer.get}")
      .append(s" ${outField.get}")
  }
}


class Gdal2Tiles ( val prog: File, val pythonExe: Option[File] ) extends GdalFileCreatorCommand {

  def setSourceSrs (srsName: String): this.type = addArg( s"-s_srs $srsName")
  def setWebViewer (webViewer: String): this.type = addArg( s"-w $webViewer")
  def setTileSize (tileSize: Int): this.type = addArg(s"--tilesize=$tileSize")
  def setProfile (profile: String): this.type = addArg( s"-p $profile")
  def setResamplingMethod (method: String): this.type = addArg( s"-r $method")
  def setXyzTiles (): this.type = addArg( "-xyz")
  def setTmsCompatible (): this.type = addArg( "-b")
  def setZoom (zoom: String): this.type = addArg(s"-z $zoom")
  def setNoDataValue(value: String): this.type = addArg( s"-a $value")
  def useMpi (): this.type = addArg( "--mpi")
  def setNumberOfProcesses (num: Int): this.type = addArg( s"--processes=$num")
  def excludeTransparentTiles(): this.type = addArg( "--x")

  override def getSuccessValue: File = {
    if (!outFile.get.isDirectory) throw new RuntimeException(s"output file not found: ${outFile.get}")
    outFile.get
  }

  override def buildCommand: StringBuilder = {
    args.foldRight(new StringBuilder(pythonExe.get.getPath + " " + prog.getPath))( (a,sb) => sb.append(' ').append(a))
      .append(s" ${inFile.get.getPath}")
      .append(s" ${outFile.get.getPath}")
  }

}