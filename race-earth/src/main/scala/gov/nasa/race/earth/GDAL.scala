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

/**
 * external proc to run GDAL executables
 */

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
    args = Seq(
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
    args = args :+ "-json"
    this
  }

  def getMinMax(): this.type = {
    args = args :+ "-mm"
    this
  }

  def getStats(): this.type = {
    args = args :+ "-stats"
    this
  }

  def getStatsApprox(): this.type = {
    args = args :+ "-approx_stats"
    this
  }

  def getHistogram(): this.type = {
    args = args :+ "-hist"
    this
  }

  def setNoGroundControlPoints(): this.type = {
    args = args :+ "-nogcp"
    this
  }

  def setNoMetadata(): this.type = {
    args = args :+ "-nomd"
    this
  }

  def setNoRasterPrint(): this.type = {
    args = args :+ "-norat"
    this
  }

  def setNoColorPrint(): this.type = {
    args = args :+ "-noct"
    this
  }

  def setCheckSum(): this.type = {
    args = args :+ "-checksum"
    this
  }

  def getMetadataList(): this.type = {
    args = args :+ "-listmdd"
    this
  }

  def setMetadata(domain: String=""): this.type = {
    args = args :+ s"-mdd $domain"
    this
  }

  def getFirstFile(): this.type = {
    args = args :+ "-nofl"
    this
  }

  def setWKTFormat(wkt: String): this.type = { // can only be wkt1, wkt2, wkt2_205, wtk2_2018
    args = args :+ s"-wkt_format $wkt"
    this
  }

  def getDataset(n: String): this.type = {
    args = args :+ s"-sd $n"
    this
  }

  def getProjection(): this.type = {
    args = args :+ "-proj4"
    this
  }

  def setDatasetOpenOption(option: String): this.type = {
    args = args :+ s"-oo $option"
    this
  }

  def setDriver(format: String): this.type = {
    args = args :+ s"-if $format"
    this
  }

}

class GdalWarp (val prog: File, val inFile: File, val outFile: File) extends ExternalProc[File] {
  
  if (!prog.isFile) throw new RuntimeException(s"executable not found: $prog")
  if (!inFile.isFile) throw new RuntimeException(s"input file not found: $inFile")

  protected override def buildCommand: String = s"${super.buildCommand} $inFile $outFile"

  override def getSuccessValue: File = {
    if (!outFile.isFile) throw new RuntimeException(s"output file not found: $outFile")
    outFile
  }

  def setWarpBand(bandN: String): this.type = {
    args = args :+ s"-b $bandN" // version 3.7 uses srcband, dstband
    this
  }

  def setTargetSrs (srsName: String): this.type = {
    // could do more sanity checks here
    args = args :+ s"-t_srs $srsName"
    this
  }

  def setSourceSrs (srsName: String): this.type = {
    args = args :+ s"-s_srs $srsName"
    this
  }

  def setSourceEpoch(epoch: String): this.type = {
    args = args :+ s"-s_coord_epoch $epoch"
    this
  }

  def setTargetEpoch(epoch: String): this.type = {
    args = args :+ s"-t_coord_epoch $epoch"
    this
  }

  def setTargetBounds(xMin:String, yMin:String, xMax:String, yMax:String): this.type = {
    args = args :+ s"-te $xMin $yMin $xMax $yMax"
    this
  }

  def setTargetBoundSrs (srsName: String): this.type = {
    args = args :+ s"-te_srs $srsName"
    this
  }

  def setCT(projName: String): this.type = {
    args = args :+ s"-CT $projName"
    this
  }

  def setTransformerOption(transformerName: String): this.type = {
    args = args :+ s"-to $transformerName"
    this
  }

  def useVerticalShift(): this.type = {
    args = args :+ "-vshift"
    this
  }

  def disableVerticalShift(): this.type = {
    args = args :+ "-novshift"
    this
  }

  def setPolynomialOrder(pOrder: String): this.type = {
    args = args :+ s"-order $pOrder" // must be between 1-3
    this
  }

  def useSpline(): this.type = {
    args = args :+ "-tps"
    this
  }

  def useRPC(): this.type = {
    args = args :+ "-rpc"
    this
  }

  def useGeolocationArrays(): this.type = {
    args = args :+ "-geoloc"
    this
  }

  def setErrorThreshold(eThresh: String): this.type = {
    args = args :+ s"-et $eThresh"
    this
  }

  def refineGCPS(tolerance: String, minGCPS: String):this.type = {
    args = args :+ s"-refine_gcps $tolerance $minGCPS"
    this
  }

  def setTargetResolution(xRes:String = "", yRes:String = "", square:String = ""): this.type = {
    args = args :+ s"-tr $xRes $yRes $square"
    this
  }

  def targetAlignedPixels(): this.type = {
    args = args :+ "-tap"
    this
  }

  def setTargetSize(width:String, height:String): this.type = {
    args = args :+ s"-ts $width $height"
    this
  }

  def setOverviewLevel(level: String = ""): this.type = { //level is optional, uses AUTO by default, can input AUTO-n or NONE as well
    args = args :+ s"-ovr $level"
    this
  }

  def setWarpOption(warpOption: String): this.type = {
    args = args :+ s"-wo $warpOption"
    this
  }

  def setTargetBandType(bandType: String): this.type = {
    args = args :+ s"-ot $bandType"
    this
  }

  def setWorkingPixelType(pixelType: String): this.type = {
    args = args :+ s"-wt $pixelType"
    this
  }

  def setResamplingMethod(method: String): this.type = {
    args = args :+ s"-r $method"
    this
  }

  def setSoruceNoDataValue(value: String = "", values: Seq[String] = Nil): this.type = { // can be a set of values or just one
    args = args :+ s"-srcnodata $value" + values.mkString(" ")
    this
  }

  def setTargetNoDataValue(value: String = "", values: Seq[String] = Nil): this.type = { // can be a set of values or just one
    args = args :+ s"-dstnodata $value" + values.mkString(" ")
    this
  }

  def setLastBandAlpha(): this.type = {
    args = args :+ "-srcalpha"
    this
  }

  def setLastBandNotAlpha(): this.type = {
    args = args :+ "-nosrcalpha"
    this
  }

  def setTargetAlpha(): this.type = {
    args = args :+ "-dstalpha"
    this
  }

  def setMemorySize(memory: String): this.type = {
    args = args :+ s"-wm $memory"
    this
  }

  def useMultithread(): this.type = {
    args = args :+ "-multi"
    this
  }

  def beQuiet(): this.type = {
    args = args :+ "-q"
    this
  }

  def setDriver(format: String): this.type = {
    args = args :+ s"-of $format"
    this
  }

  def setCreationOptions(option: String): this.type = {
    args = args :+ s"-co $option"
    this
  }

  def setCutline(dataSource: String): this.type = {
    args = args :+ s"-cutline $dataSource"
    this
  }

  def setCutlineLayer(layerName: String): this.type = {
    args = args :+ s"-cl $layerName"
    this
  }

  def setCutlineRestriction(expression: String): this.type = {
    args = args :+ s"-cwhere $expression"
    this
  }

  def setCutlineSQL(query: String): this.type = {
    args = args :+ s"-csql $query"
    this
  }

  def setCutlineBlend(distance: String): this.type = {
    args = args :+ s"-cblend $distance"
    this
  }

  def setCropToCutline(): this.type = {
    args = args :+ "-crop_to_cutline"
    this
  }

  def setOverwrite(): this.type = {
    args = args :+ "-overwrite"
    this
  }

  def setNoCopyMetadata(): this.type = {
    args = args :+ "-nomd"
    this
  }

  def setMetadataConflicts(conflictValue: String): this.type = {
    args = args :+ s"-cvmd $conflictValue"
    this
  }

  def setColorInterpretation(): this.type = {
    args = args :+ "-setci"
    this
  }

  def setSourceOpenOption(option: String): this.type = {
    args = args :+ s"-oo $option"
    this
  }

  def setDestinationOpenOption(option: String): this.type = {
    args = args :+ s"-doo $option"
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
    args = args :+ s"-ot $outputType"
    this
  }

  def setStrict(): this.type = {
    args = args :+ "=strict"
    this
  }

  def setInputDriver(format: String): this.type = {
    args = args :+ s"-if $format"
    this
  }

  def setOutputDriver(format: String): this.type = {
    args = args :+ s"-of $format"
    this
  }

  def setBand(bandN: String): this.type = {
    args = args :+ s"-b $bandN"
    this
  }

  def setMask(bandN: String): this.type = {
    args = args :+ s"-mask $bandN"
    this
  }

  def setExpandColorTable(colorTable: String): this.type = {
    // can be gray, rgb, or rgba
    args = args :+ s"-expand $colorTable"
    this
  }

  def setOutputSize(xSize: String = "0", ySize: String = "0"): this.type = {
    // can be gray, rgb, or rgba
    args = args :+ s"-outsize $xSize $ySize"
    this
  }

  def setTargetResolution(xRes:String, yRes:String): this.type = {
    args = args :+ s"-tr $xRes $yRes"
    this
  }

  def setOverviewLevel(level: String = ""): this.type = { //level is optional, uses AUTO by default, can input AUTO-n or NONE as well
    args = args :+ s"-ovr $level"
    this
  }

  def setResamplingMethod(method: String): this.type = {
    args = args :+ s"-r $method"
    this
  }

  def setScale(src_min: String = "", src_max: String = "", dst_min: String = "", dst_max: String = ""): this.type = {
    args = args :+ s"-scale $src_min $src_max $dst_min $dst_max"
    this
  }

  def setScaleExponent(exp: Int): this.type = {
    if (!args.contains("-scale")) throw new RuntimeException(s"Scaling exponent can only be used following setScale()")
    args = args :+ s"-exponent $exp"
    this
  }

  def setUnscale(): this.type = {
    args = args :+ "-unscale"
    this
  }

  def setWindowPixels(xOff: String, yOff: String, xSize: String, ySize: String): this.type = {
    args = args :+ s"-srcwin $xOff $yOff $xSize $ySize"
    this
  }
  def setWindowCoords(ulx: String, uly: String, lrx: String, lry: String): this.type = {
      args = args :+ s"-projwin $ulx $uly $lrx $lry"
      this
  }

  def setWindowSrs(srsName: String): this.type = {
    args = args :+ s"-projwin_srs $srsName"
    this
  }

  def setPartiallyOutsideError(): this.type = {
    args = args :+ "-epo"
    this
  }

  def setCompletelyOutsideError(): this.type = {
    args = args :+ "-eco"
    this
  }

  def setCoordEpoch(epoch: String): this.type = {
    args = args :+ s"-a_coord_epoch $epoch"
    this
  }

  def setBandScale(value: String): this.type = {
    args = args :+ s"-a_scale $value"
    this
  }

  def setBandOffset(value: String): this.type = {
    args = args :+ s"-a_offset $value"
    this
  }

  def setOutputBounds(ulx: String, uly: String, lrx: String, lry: String): this.type = {
      args = args :+ s"-a_ullr $ulx $uly $lrx $lry"
      this
  }

  def setNoDataValue(value: String): this.type = {
    args = args :+ s"-a_nodata $value"
    this
  }

  def setBandColorInterpretation(bandN: String, color: String): this.type = {
    args = args :+ s"-colorinterp_$bandN $color"
    this
  }

  def setColorInterpretation(colors: Seq[String]): this.type = {
    val colorsString = colors.mkString(",")
    args = args :+ s"-colorinterp $colorsString"
    this
  }

  def setMetaData(key: String, value: String): this.type = {
    args = args :+ s"-mo $key=$value"
    this
  }

  def setCreationOptions(option: String): this.type = {
    args = args :+ s"-co $option"
    this
  }

  def setNoGroundControlPoints(): this.type = {
    args = args :+ "-nogcp"
    this
  }

  def setGroundControlPoints(pixel: String, line: String, easting: String, northing: String, elevation: String): this.type = {
    args = args :+ s"-gcp $pixel $line $easting $elevation"
    this
  }

  def beQuiet(): this.type = {
    args = args :+ "-q"
    this
  }

  def setCopyDatasets(): this.type = {
    args = args :+ "-sds"
    this
  }

  def getStats(): this.type = {
    args = args :+ "-stats"
    this
  }

  def setNoRAT(): this.type = {
    args = args :+ "-norat"
    this
  }

  def setNoXMP(): this.type = {
    args = args :+ "-noxmp"
    this
  }

  def setDatasetOpenOption(option: String): this.type = {
    args = args :+ s"-oo $option"
    this
  }

}