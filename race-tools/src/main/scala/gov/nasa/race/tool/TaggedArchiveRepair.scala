/*
 * Copyright (c) 2021, United States Government, as represented by the
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

import gov.nasa.race.archive.{TaggedArchiveReader, TaggedArchiveWriter, TaggedStringArchiveWriter}
import gov.nasa.race.common.ByteSlice
import gov.nasa.race.ifSome
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import java.io.{File, FileOutputStream, InputStream, OutputStream}

/**
  * a command line tool that takes TaggedArchives and rewrites archive entry headers based on computed
  * entry payload length. The tool also supports adjusting the start date or the time-scale of the entry dates
  *
  * This is useful in case a TaggedArchive was modified manually
  */
object TaggedArchiveRepair {

  class RepairReader (val iStream: InputStream) extends TaggedArchiveReader {
    override val initBufferSize: Int = 8192 // we don't care
    override protected def parseEntryData(limit: Int): Any = None // not used
    override val pathName: String = "" // not used

    def repair(w: TaggedArchiveWriter, opt: Opts): Unit = {
      if (initialized) {
        ifSome(opt.startDate){ sd=>
          println(f"setting reference date from $refDate (${refDate.toEpochMillis}%08x) to $sd (${sd.toEpochMillis}%08x)")
          refDate = sd
        }
        w.open(refDate, new String(extraFileHeader))

        readToNextEntryHeaderStart() // skip to beginning of first entry header

        while (readEntryHeaderFields()) {
          val outEntryLength = readToNextEntryHeaderStart()

          val outEntryDate = opt.timeScale match {
            case Some(ts) => refDate + entryDate.timeSince(refDate) * ts
            case None => entryDate
          }

          if (outEntryLength > 0) {
            if (outEntryDate != entryDate && opt.startDate.isEmpty) {
              val d = entryDate.timeSince(refDate)
              val dNew = outEntryDate.timeSince(refDate)
              println(f"changed entry time +$d (${d.toMillis}%08x) to +$dNew (${dNew.toMillis}%08x)")
            }

            if (outEntryLength != entryLength) {
              val d = outEntryDate.timeSince(refDate)
              println(f"changed entry length at time point +$d (${d.toMillis}%08x) from $entryLength ($entryLength%08x) to $outEntryLength ($outEntryLength%08x)")
            }

            w.write( outEntryDate, new String(buf,0,outEntryLength)) // not very efficient
          }
        }

        w.close()
      }
    }
  }

  class RepairWriter (val oStream: OutputStream) extends TaggedArchiveWriter {
    override protected def setEntryBytes(obj: Any): Unit = {
      obj match {
        case a: Array[Byte] => entryData.set(a, 0, a.length)
        case slice: ByteSlice => entryData.setFrom(slice)
        case _ => entryData.clear()
      }
    }

    override val pathName: String = "" // not used
  }

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var inFile: Option[File] = None // the text archive file to extract from
    var outFile: Option[File] = None // optional file to store matches in
    var startDate: Option[DateTime] = None // optional start date to use in output
    var timeScale: Option[Double] = None // optional time scale to adjust entry dates in output

    opt1("-o", "--out")("<pathName>", s"optional pathname of file to store matching messages (default = $outFile)") { pn =>
      outFile = Some(new File(pn))
    }

    opt1("--start-date")("<date>", "optional start time to use in output (e.g. \"2017-08-08T00:44:12Z\")") { s=>
      startDate = Some(parseDateTime(s))
    }

    opt1("--time-scale")("<factor>", "optional time scale to adjust entry dates in output") { s=>
      timeScale = Some(parseDouble(s))
    }

    requiredArg1("<pathName>", "text archive to repair") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (file <- opts.inFile;
           is <- FileUtils.inputStreamFor(file,8192)){
        val oFile = opts.outFile.getOrElse( new File(file.getPath + ".fix"))
        val os = new FileOutputStream(oFile)

        val w = new TaggedStringArchiveWriter(os,oFile.getPath)
        val r = new RepairReader(is)

        r.repair(w, opts)

        is.close()
        os.close()

        if (opts.outFile.isEmpty) {
          println(s"archive $oFile rewritten (old version saved to *.bak).")
          file.renameTo(new File(file.getPath + ".bak"))
          oFile.renameTo(file)
        }
      }
    }
  }
}
