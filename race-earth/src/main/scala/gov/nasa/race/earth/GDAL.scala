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
import gov.nasa.race.util.FileUtils

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

  override def buildCommand: String = {
    args += inFile.get.getPath
    args += outFile.get.getPath

    super.buildCommand
  }

  override protected def getSuccessValue: File = {
    if (outFile.isDefined) {
      if (outFile.get.isFile) outFile.get
      else throw new RuntimeException(s"outFile not found: ${outFile.get}")
    } else throw new RuntimeException("no outFile set")
  }
}


class Gdal2Tiles (val prog: File, val inFile: File, val outputPath: File, driverPath: File,
                  srsName: String = "EPSG:4326", webviewer: String = "none",
                  tileSize: Int = 256,  profile: String = "mercator", resampling: String = "average",
                  zoom: Option[Int] = None, resume: Boolean = false, noDataValue: Option[Double] = None,
                  tmsCompatible: Boolean = false, verbose: Boolean = true, kml: Boolean = false,
                  googleKey: Option[String] = None, bingKey: Option[String] = None, nbProcesses: Int = 1
                 ) extends ExternalProc[File] {

  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!driverPath.isFile) throw new RuntimeException(s"gdal2tiles driver not found: $driverPath")
  if (!inFile.isFile) throw new RuntimeException(s"input file not found: $inFile")
  if (!FileUtils.ensureWritableDir(outputPath).isDefined) throw new RuntimeException(s"cannot create gdal tiles output dir $outputPath")

  protected override def buildCommand: String = {
    args ++= Seq(
       s"$driverPath",
       s"s_srs=$srsName",
       s"webviewer=$webviewer",
       s"tile_size=$tileSize",
       s"profile=$profile",
       s"resampling=$resampling", 
       s"zoom=$zoom",
       s"resume=$resume", 
       s"srcnodata=$noDataValue",
       s"tmscompatible=$tmsCompatible",
       s"verbose=$verbose",
       s"kml=$kml",
       s"googlekey=$googleKey",
       s"bingkey=$bingKey", 
       s"nbprocesses=$nbProcesses",
       s"$inFile",
       s"$outputPath"
    )
    super.buildCommand
  }

  override def getSuccessValue: File = {
    if (!outputPath.isDirectory) throw new RuntimeException(s"output file not found: $outputPath")
    outputPath
  }

}

class GdalInfo (val prog: File, val inFile: File) extends ExternalProc[File]  {
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!inFile.isFile) throw new RuntimeException(s"input file not found: $inFile")

  protected override def buildCommand: String = s"${super.buildCommand} $inFile"

  override def getSuccessValue: File = {
    if (!inFile.isFile) throw new RuntimeException(s"output file not found: $inFile")
    inFile
  }
  def getJson(): this.type = {
    args += "-json"
    this
  }

  def getMinMax(): this.type = {
    args += "-mm"
    this
  }

  def getStats(): this.type = {
    args += "-stats"
    this
  }

  def getStatsApprox(): this.type = {
    args += "-approx_stats"
    this
  }

  def getHistogram(): this.type = {
    args += "-hist"
    this
  }

  def setNoGroundControlPoints(): this.type = {
    args += "-nogcp"
    this
  }

  def setNoMetadata(): this.type = {
    args += "-nomd"
    this
  }

  def setNoRasterPrint(): this.type = {
    args += "-norat"
    this
  }

  def setNoColorPrint(): this.type = {
    args += "-noct"
    this
  }

  def setCheckSum(): this.type = {
    args += "-checksum"
    this
  }

  def getMetadataList(): this.type = {
    args += "-listmdd"
    this
  }

  def setMetadata(domain: String=""): this.type = {
    args += s"-mdd $domain"
    this
  }

  def getFirstFile(): this.type = {
    args += "-nofl"
    this
  }

  def setWKTFormat(wkt: String): this.type = { // can only be wkt1, wkt2, wkt2_205, wtk2_2018
    args += s"-wkt_format $wkt"
    this
  }

  def getDataset(n: String): this.type = {
    args += s"-sd $n"
    this
  }

  def getProjection(): this.type = {
    args += "-proj4"
    this
  }

  def setDatasetOpenOption(option: String): this.type = {
    args += s"-oo $option"
    this
  }

  def setDriver(format: String): this.type = {
    args += s"-if $format"
    this
  }
}

/**
 * a generic gdalwarp wrapper
 * TODO - still needs to handle checks for repetitive setters etc.
 */
class GdalWarp (val prog: File) extends GdalFileCreatorCommand {
  
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")

  def setWarpBand(bandN: String): this.type = {
    args += s"-b $bandN" // version 3.7 uses srcband, dstband
    this
  }

  def setTargetSrs (srsName: String): this.type = {
    // could do more sanity checks here
    args += s"-t_srs $srsName"
    this
  }

  def setSourceSrs (srsName: String): this.type = {
    args += s"-s_srs $srsName"
    this
  }

  def setSourceEpoch(epoch: String): this.type = {
    args += s"-s_coord_epoch $epoch"
    this
  }

  def setTargetEpoch(epoch: String): this.type = {
    args += s"-t_coord_epoch $epoch"
    this
  }

  def setTargetBounds(xMin:String, yMin:String, xMax:String, yMax:String): this.type = {
    args += s"-te $xMin $yMin $xMax $yMax"
    this
  }

  def setTargetBoundSrs (srsName: String): this.type = {
    args += s"-te_srs $srsName"
    this
  }

  def setCT(projName: String): this.type = {
    args += s"-CT $projName"
    this
  }

  def setTransformerOption(transformerName: String): this.type = {
    args += s"-to $transformerName"
    this
  }

  def useVerticalShift(): this.type = {
    args += "-vshift"
    this
  }

  def disableVerticalShift(): this.type = {
    args += "-novshift"
    this
  }

  def setPolynomialOrder(pOrder: String): this.type = {
    args += s"-order $pOrder" // must be between 1-3
    this
  }

  def useSpline(): this.type = {
    args += "-tps"
    this
  }

  def useRPC(): this.type = {
    args += "-rpc"
    this
  }

  def useGeolocationArrays(): this.type = {
    args += "-geoloc"
    this
  }

  def setErrorThreshold(eThresh: String): this.type = {
    args += s"-et $eThresh"
    this
  }

  def refineGCPS(tolerance: String, minGCPS: String):this.type = {
    args += s"-refine_gcps $tolerance $minGCPS"
    this
  }

  def setTargetResolution(xRes:String = "", yRes:String = "", square:String = ""): this.type = {
    args += s"-tr $xRes $yRes $square"
    this
  }

  def targetAlignedPixels(): this.type = {
    args += "-tap"
    this
  }

  def setTargetSize(width:String, height:String): this.type = {
    args += s"-ts $width $height"
    this
  }

  def setOverviewLevel(level: String = ""): this.type = { //level is optional, uses AUTO by default, can input AUTO-n or NONE as well
    args += s"-ovr $level"
    this
  }

  def setWarpOption(warpOption: String): this.type = {
    args += s"-wo $warpOption"
    this
  }

  def setTargetBandType(bandType: String): this.type = {
    args += s"-ot $bandType"
    this
  }

  def setWorkingPixelType(pixelType: String): this.type = {
    args += s"-wt $pixelType"
    this
  }

  def setResamplingMethod(method: String): this.type = {
    args += s"-r $method"
    this
  }

  def setSoruceNoDataValue(value: String = "", values: Seq[String] = Nil): this.type = { // can be a set of values or just one
    args += s"-srcnodata $value" + values.mkString(" ")
    this
  }

  def setTargetNoDataValue(value: String = "", values: Seq[String] = Nil): this.type = { // can be a set of values or just one
    args += s"-dstnodata $value" + values.mkString(" ")
    this
  }

  def setLastBandAlpha(): this.type = {
    args += "-srcalpha"
    this
  }

  def setLastBandNotAlpha(): this.type = {
    args += "-nosrcalpha"
    this
  }

  def setTargetAlpha(): this.type = {
    args += "-dstalpha"
    this
  }

  def setMemorySize(memory: String): this.type = {
    args += s"-wm $memory"
    this
  }

  def useMultithread(): this.type = {
    args += "-multi"
    this
  }

  def beQuiet(): this.type = {
    args += "-q"
    this
  }

  override def setOutDriver(format: String): this.type = {
    args += s"-of $format"
    this
  }

  def setCreationOptions(option: String): this.type = {
    args += s"-co $option"
    this
  }

  def setCutline(dataSource: String): this.type = {
    args += s"-cutline $dataSource"
    this
  }

  def setCutlineLayer(layerName: String): this.type = {
    args += s"-cl $layerName"
    this
  }

  def setCutlineRestriction(expression: String): this.type = {
    args += s"-cwhere $expression"
    this
  }

  def setCutlineSQL(query: String): this.type = {
    args += s"-csql $query"
    this
  }

  def setCutlineBlend(distance: String): this.type = {
    args += s"-cblend $distance"
    this
  }

  def setCropToCutline(): this.type = {
    args += "-crop_to_cutline"
    this
  }

  def setOverwrite(): this.type = {
    args += "-overwrite"
    this
  }

  def setNoCopyMetadata(): this.type = {
    args += "-nomd"
    this
  }

  def setMetadataConflicts(conflictValue: String): this.type = {
    args += s"-cvmd $conflictValue"
    this
  }

  def setColorInterpretation(): this.type = {
    args += "-setci"
    this
  }

  def setSourceOpenOption(option: String): this.type = {
    args += s"-oo $option"
    this
  }

  def setDestinationOpenOption(option: String): this.type = {
    args += s"-doo $option"
    this
  }

}

class GdalTranslate (val prog: File, val inFile: File, val outFile: File) extends ExternalProc[File] {
  
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!inFile.isFile) throw new RuntimeException(s"input file not found: $inFile")

  protected override def buildCommand: String = s"${super.buildCommand} $inFile $outFile"
  
  override def getSuccessValue: File = {
    if (!outFile.isFile) throw new RuntimeException(s"output file not found: $outFile")
    outFile
  }

  def setOutputType(outputType: String): this.type = {
    // can only be: Byte, Int8, UInt16, Int16, UInt32, Int32, UInt64, Int64, Float32, Float64, CInt16, CInt32, CFloat32 or CFloat64
    args += s"-ot $outputType"
    this
  }

  def setStrict(): this.type = {
    args += "=strict"
    this
  }

  def setInputDriver(format: String): this.type = {
    args += s"-if $format"
    this
  }

  def setOutputDriver(format: String): this.type = {
    args += s"-of $format"
    this
  }

  def setBand(bandN: String): this.type = {
    args += s"-b $bandN"
    this
  }

  def setMask(bandN: String): this.type = {
    args += s"-mask $bandN"
    this
  }

  def setExpandColorTable(colorTable: String): this.type = {
    // can be gray, rgb, or rgba
    args += s"-expand $colorTable"
    this
  }

  def setOutputSize(xSize: String = "0", ySize: String = "0"): this.type = {
    // can be gray, rgb, or rgba
    args += s"-outsize $xSize $ySize"
    this
  }

  def setTargetResolution(xRes:String, yRes:String): this.type = {
    args += s"-tr $xRes $yRes"
    this
  }

  def setOverviewLevel(level: String = ""): this.type = { //level is optional, uses AUTO by default, can input AUTO-n or NONE as well
    args += s"-ovr $level"
    this
  }

  def setResamplingMethod(method: String): this.type = {
    args += s"-r $method"
    this
  }

  def setScale(src_min: String = "", src_max: String = "", dst_min: String = "", dst_max: String = ""): this.type = {
    args += s"-scale $src_min $src_max $dst_min $dst_max"
    this
  }

  def setScaleExponent(exp: Int): this.type = {
    if (!args.contains("-scale")) throw new RuntimeException(s"Scaling exponent can only be used following setScale()")
    args += s"-exponent $exp"
    this
  }

  def setUnscale(): this.type = {
    args += "-unscale"
    this
  }

  def setWindowPixels(xOff: String, yOff: String, xSize: String, ySize: String): this.type = {
    args += s"-srcwin $xOff $yOff $xSize $ySize"
    this
  }
  def setWindowCoords(ulx: String, uly: String, lrx: String, lry: String): this.type = {
      args += s"-projwin $ulx $uly $lrx $lry"
      this
  }

  def setWindowSrs(srsName: String): this.type = {
    args += s"-projwin_srs $srsName"
    this
  }

  def setPartiallyOutsideError(): this.type = {
    args += "-epo"
    this
  }

  def setCompletelyOutsideError(): this.type = {
    args += "-eco"
    this
  }

  def setCoordEpoch(epoch: String): this.type = {
    args += s"-a_coord_epoch $epoch"
    this
  }

  def setBandScale(value: String): this.type = {
    args += s"-a_scale $value"
    this
  }

  def setBandOffset(value: String): this.type = {
    args += s"-a_offset $value"
    this
  }

  def setOutputBounds(ulx: String, uly: String, lrx: String, lry: String): this.type = {
      args += s"-a_ullr $ulx $uly $lrx $lry"
      this
  }

  def setNoDataValue(value: String): this.type = {
    args += s"-a_nodata $value"
    this
  }

  def setBandColorInterpretation(bandN: String, color: String): this.type = {
    args += s"-colorinterp_$bandN $color"
    this
  }

  def setColorInterpretation(colors: Seq[String]): this.type = {
    val colorsString = colors.mkString(",")
    args += s"-colorinterp $colorsString"
    this
  }

  def setMetaData(key: String, value: String): this.type = {
    args += s"-mo $key=$value"
    this
  }

  def setCreationOptions(option: String): this.type = {
    args += s"-co $option"
    this
  }

  def setNoGroundControlPoints(): this.type = {
    args += "-nogcp"
    this
  }

  def setGroundControlPoints(pixel: String, line: String, easting: String, northing: String, elevation: String): this.type = {
    args += s"-gcp $pixel $line $easting $elevation"
    this
  }

  def beQuiet(): this.type = {
    args += "-q"
    this
  }

  def setCopyDatasets(): this.type = {
    args += "-sds"
    this
  }

  def getStats(): this.type = {
    args += "-stats"
    this
  }

  def setNoRAT(): this.type = {
    args += "-norat"
    this
  }

  def setNoXMP(): this.type = {
    args += "-noxmp"
    this
  }

  def setDatasetOpenOption(option: String): this.type = {
    args += s"-oo $option"
    this
  }

}


class GdalContour (val prog: File) extends GdalFileCreatorCommand {
  var bandNo: Option[Int] = None
  var interval: Option[Double] = None

  override def canRun: Boolean = bandNo.isDefined && interval.isDefined && super.canRun

  override def buildCommand: String = {
    args += s"-b ${bandNo.get}"
    args += s"-i ${interval.get}"

    super.buildCommand
  }

  def setBand(n:Int): this.type = {
    bandNo = Some(n)
    this
  }

  def setInterval(d: Double): this.type = {
    interval = Some(d)
    this
  }

  def setAttrName (name: String): this.type = {
    args += s"-a $name"
    this
  }

  def setAttrMinName (name: String): this.type = {
    args += s"-amin $name"
    this
  }

  def setAttrMaxName (name: String): this.type = {
    args += s"-amax $name"
    this
  }

  def setPolygon (): this.type = {
    args += "-p"
    this
  }
}