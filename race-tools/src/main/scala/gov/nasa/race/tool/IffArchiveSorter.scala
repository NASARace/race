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
package gov.nasa.race.tool

import gov.nasa.race.common.{LineBuffer, Utf8CsvPullParser}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.{ConsoleIO, FileUtils}

import java.io.{File, FileOutputStream, PrintStream}

/**
  * tool to time-sort Sherlock IFF archives (CSV files  from https://sherlock.opendata.arc.nasa.gov/sherlock_open/DownloadHome
  * with IFF records that are only time-sorted within the same flight. We sort strictly on the field 2 timestamp, which
  * is of format <epoch-second>.<millis>. Format documentation is on https://sherlock.opendata.arc.nasa.gov/api/docs/#/products/IFF
  *
  * Note that archives can be quite large (exceeding 1M lines) so running this tool might require a lot of memory. The
  * implementation is just basic and does not try to minimize memory (e.g. by splitting the input into per-aircraft
  * lists and merge-sorting from those). Since we normally don't look at time spans > 3h this is acceptable (for now)
  *
  * 2,1598126710.195,22797,3212,652,NCT,,N201FQ,1,M20P,DD1,AA1,O,?,?,000000
  * 4,1598127587.178,22797,3212,652,NCT,AIG200,N201FQ,1,M20P,BB1,AA1,N,,,,0,?,,V,P,O,/F,,  ...
  * ...
  * 4,1598132512.788,22797,3212,652,NCT,AIG200,N201FQ,1,M20P,DD1,AA1,N,,,,,?,,V,P,O,/F,,E,,SNA,,,,,,,,,
  * 3,1598126710.195,22797,3212,652,NCT,AIG200,N201FQ,1,38.99925,-123.11498,39.00,1,0.500,0.500,,85,153,498, ...
  * 3,1598126722.235,22797,3212,652,NCT,AIG200,N201FQ,1,38.99515,-123.11231,40.00,6,0.500,0.500,,85,153,498,,,0, ...
  * ...
  * 2,1598130025.973,22798,4273,156,NCT,,N86CG,1,C525,RBL,RDD,A,SJC,RDD,000000
  * 4,1598130025.973,22798,4273,156,NCT,AIG200,N86CG,1,C525,SJC,RBL,N,,,,,?,,I,J,D,/F,2004,P,,,,,,,,,,,
  * 4,1598132365.723,22798,4273,156,NCT,AIG200,N86CG,1,C525,RBL,RDD,N,,,,0,?,,I,J,A,/F,2136,A,,,,,,,,,,,
  * 3,1598130025.973,22798,4273,156,NCT,AIG200,N86CG,1,37.36406,-121.93188,5.00,1,0.500,0.500,,168,315,1200,,,0, ...
  * 3,1598130030.901,22798,4273,156,NCT,AIG200,N86CG,1,37.36668,-121.93520,7.00,6,0.500,0.500,,168,315,1200,,,0, ...
  * ...
  */

class IffSortOpts extends CliArgs("usage:") {
  var inFile: Option[File] = None // the text archive file to extract from
  var outFile: Option[File] = None // optional file to store matches in
  var startDate: Option[DateTime] = None // optional start date filter
  var endDate: Option[DateTime] = None // optional end date filter

  opt1("-o", "--out")("<pathName>", s"optional pathname of file to store matching messages (default = $outFile)") { pn =>
    outFile = Some(new File(pn))
  }
  opt1("--start-date")("<date>", "optional start time filter (e.g. \"2017-08-08T00:44:12Z\")") { s=>
    startDate = Some(parseDateTime(s))
  }
  opt1("--end-date")("<date>", "optional end time filter (e.g. \"2017-08-08T00:44:12Z\")") { s=>
    endDate = Some(parseDateTime(s))
  }
  requiredArg1("<pathName>", "IFF archive to sort") { a =>
    inFile = parseExistingFileOption(a)
  }
}

object IffArchiveSorter {
  class Entry (val date: DateTime, val line: String, var next: Entry)

  var opts = new IffSortOpts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      for (file <- opts.inFile; is <- FileUtils.inputStreamFor(file,32768)) {
        val lb = new LineBuffer(is)
        val parser = new Utf8CsvPullParser {}
        val start = if (opts.startDate.isDefined) opts.startDate.get else DateTime.UndefinedDateTime
        val end = if (opts.endDate.isDefined) opts.endDate.get else DateTime.UndefinedDateTime
        var head: Entry = null
        var nLines = 0
        var eLast: Entry = null // the last entry we inserted/appended

        val ps = opts.outFile match {
          case Some(oFile) => new PrintStream(new FileOutputStream(oFile))
          case None => System.out
        }
        if (ps != System.out) print("sorting ...")

        while (lb.nextLine()) {
          if (parser.initialize(lb)){
            val msgType = parser.readNextValue().toInt
            if (msgType >= 2 && msgType <= 4) { // ignore 0, 1, 5
              val date = DateTime.ofEpochFractionalSeconds(parser.readNextValue().toDouble)

              if ((start.isUndefined || date >= start) && (end.isUndefined || date <= end)) {
                val line = lb.asString

                if (head == null) {
                  head = new Entry(date, line, null)
                  eLast = head

                } else {
                  var e: Entry = if (eLast.date > date) { eLast = null; head } else eLast
                  while (e != null && e.date <= date) { eLast = e; e = e.next }

                  if (e != null) { // insert or prepend
                    if (e eq head) {
                      head = new Entry(date,line,head)
                      eLast = head
                    } else {
                      eLast.next = new Entry(date, line, e)
                      eLast = eLast.next
                    }

                  } else { // append
                    eLast.next = new Entry(date, line, null)
                    eLast = eLast.next
                  }
                }

                nLines += 1
                if (ps != System.out && nLines % 10000 == 0) print(s"${ConsoleIO.ClearLine}$nLines records sorted...")
              }
            }
          }
        }

        if (ps != System.out) {
          print(s"${ConsoleIO.ClearLine}writing $nLines records ...")
          var e = head
          while (e != null) { ps.println(e.line); e = e.next }
          println(s"${ConsoleIO.ClearLine}wrote $nLines records to ${opts.outFile.get}.")
        }
      }
    }
  }
}
