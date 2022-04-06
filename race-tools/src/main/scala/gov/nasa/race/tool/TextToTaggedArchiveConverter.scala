/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.tool

import java.io.{File, InputStream, OutputStream}

import gov.nasa.race.air.{AsdexDWArchiveReader, SfdpsDWArchiveReader, TaisDWArchiveReader, TfmdataDWArchiveReader}
import gov.nasa.race.archive.{ArchiveReader, ArchiveWriter, TaggedStringArchiveWriter, TextArchiveReader}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

/**
  * convert old-style TextArchive to TaggedArchive format
  */
object TextToTaggedArchiveConverter {

  val FMT_TEXT_ARCHIVE = 1
  val FMT_DW_ARCHIVE = 2

  class Opts extends CliArgs(s"convert text or DW archive to tagged archive format") {
    var inFile: Option[File] = None // the text archive file to extract from
    var outFile: Option[File] = None // optional file to store matches in
    var dwTopic: Option[String] = None // if set we convert DW archive

    opt1("-o", "--out")("<pathName>", s"optional pathname of converted archive (default = <baseName>.ta)") { pn =>
      outFile = Some(new File(pn))
    }
    opt1("--dw")("<swim-topic>", "convert DataWarehouse input archive for <swim-topic> (default is text archive)") { topic =>
      dwTopic = Seq("sfdps","tais","tfmdata","asdex").find(_ == topic)
    }
    requiredArg1("<pathName>", "text archive to convert") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (inFile <- opts.inFile;
           is <- FileUtils.inputStreamFor(inFile,8192);
           outFile <- opts.outFile orElse deriveOutputFile(inFile);
           os <- FileUtils.outputStreamFor(outFile) ){
        val ar = createArchiveReader(is, inFile.getPath)
        val aw = createArchiveWriter(os, outFile.getPath)
        processArchiveEntries(ar, aw)
        is.close()
      }
    }
  }

  def processArchiveEntries(ar: ArchiveReader, aw: ArchiveWriter): Unit = {
    var baseDate = DateTime.UndefinedDateTime
    var nEntries = 0

    println(s"converting archive ${ar.pathName} to ${aw.pathName}")

    while (ar.hasMoreArchivedData) {
      ar.readNextEntry() match {
        case Some(e) =>
          val date: DateTime = e.date
          val msg = e.msg.toString

          if (baseDate.isUndefined) { // first entry - write header
            baseDate = date
            aw.open(baseDate,s"converted from ${ar.pathName}")
          }

          nEntries += 1
          aw.write(date,msg)

        case None => // done
      }
    }

    ar.close()
    aw.close()

    println(s"$nEntries entries written.")
  }

  def deriveOutputFile (inFile: File): Option[File] = {
    val inName = inFile.getName
    val isCompressed = inName.endsWith(".gz")
    val extIdx = inName.indexOf('.')
    val baseName = inName.substring(0,extIdx)

    val outName = if (isCompressed) s"$baseName.ta.gz" else s"$baseName.ta"
    Some(new File(inFile.getParent,outName))
  }

  def createArchiveReader (is: InputStream, pathName: String): ArchiveReader = {
    opts.dwTopic match {
      case Some("sfdps") => new SfdpsDWArchiveReader(is,pathName)
      case Some("tais") => new TaisDWArchiveReader(is,pathName)
      case Some("asdex") => new AsdexDWArchiveReader(is,pathName)
      case Some("tfmdata") => new TfmdataDWArchiveReader(is,pathName)

      case _ => new TextArchiveReader(is,pathName) // not a DW archive
    }
  }

  def createArchiveWriter (os: OutputStream, pathName: String): ArchiveWriter = {
    new TaggedStringArchiveWriter(os,pathName, 32768)
  }
}
