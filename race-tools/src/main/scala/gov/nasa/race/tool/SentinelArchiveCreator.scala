package gov.nasa.race.tool

import gov.nasa.race.archive.TaggedStringArchiveWriter
import gov.nasa.race.common.{JsonArray, JsonLong, JsonObject, JsonPrintOptions, JsonValue, UTF8JsonPullParser}
import gov.nasa.race.uom.Time.Seconds
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.{ArrayUtils, FileUtils}

import java.io.{ByteArrayOutputStream, File, PrintStream}

object SentinelArchiveCreator {
  def main (args: Array[String]): Unit = {
    if (args.length < 2) {
      println("usage: SentinelArchiveCreator <out-archive> <in-file>...")

    } else {
      FileUtils.ensureWritable(args(0)) match {
        case Some(outFile) =>
          val inPaths = ArrayUtils.tail(args)
          val inFiles = FileUtils.existingNonEmptyFiles(inPaths).toArray
          if (inFiles.nonEmpty) {
            println(s"writing to archive: $outFile")
            val creator = new SentinelArchiveCreator(outFile,inFiles)
            try {
              creator.parseInput()
              creator.createArchive()
            } catch {
              case x: Throwable => println(s"error parsing JSON: $x")
            }

          } else {
            println(s"no readable non-empty input files in [${inPaths.mkString}]")
          }

        case None => println(s"not writable: ${args(0)}}")
      }
    }
  }
}

class SentinelArchiveCreator (val outFile: File, val inFiles: Array[File]) {
  val os = FileUtils.outputStreamFor(outFile).get
  val writer = new TaggedStringArchiveWriter( os, outFile.getPath, 32768)
  val jp = new UTF8JsonPullParser
  var inJsons = Array.empty[JsonValue]

  def parseInput(): Unit = {
    inJsons = inFiles.flatMap { f =>
      println(s"parsing $f..")
      jp.initialize(FileUtils.fileContentsAsBytes(f).get)
      jp.parseJsonValue
    }
  }

  def printInJsons(): Unit = {
    for (json <- inJsons) {
      json.printOn(System.out, JsonPrintOptions(noNullMembers = true, pretty = true))
      println()
    }
  }

  def getDataArrays: Array[JsonArray] = {
    inJsons.flatMap { jv =>
      jv match {
        case jsonObject: JsonObject => jsonObject.get("data").flatMap { jv =>
          jv match {
            case ja: JsonArray =>Some(ja)
            case _ => None
          }
        }
        case _ => None
      }
    }
  }

  def concatenateDataArrays( datas: Array[Array[JsonObject]]): Array[JsonObject] = {
    val len: Int = datas.foldLeft(0)( (acc,a) => acc + a.length)
    val recs = new Array[JsonObject](len)

    var i=0
    datas.foreach { a =>
      System.arraycopy(a, 0, recs, i, a.length)
      i += a.length
    }

    recs
  }

  def createArchive(): Unit = {
    @inline def timeStamp(o: JsonObject): Long = o("timeRecorded").asInstanceOf[JsonLong].value
    def isBefore (a: JsonObject, b: JsonObject): Boolean = timeStamp(a) < timeStamp(b)

    val datas: Array[Array[JsonObject]] = getDataArrays.map( da => da.elementsArray.flatMap{ jv=>
      if (jv.isInstanceOf[JsonObject] && jv.asInstanceOf[JsonObject].get("timeRecorded").isDefined) Some(jv.asInstanceOf[JsonObject])
      else None // element is not a JsonObject or does not have a timeRecorded member
    })

    val unsortedRecs = concatenateDataArrays(datas)
    println(s"sorting ${unsortedRecs.length} records..")
    val recs = unsortedRecs.sortWith(isBefore)

    println("writing archive..")
    val dt: Time = Seconds(1) // time in sec between record date and simulated receive data  TODO - this should be a cli argument
    val queryInterval: Time = Seconds(20)  // TODO - this should be a cli argument
    var startDate: DateTime = DateTime.ofEpochSeconds(timeStamp(recs.head)) + dt  // we could get that from command line args
    writer.open(startDate,writer.getClass.getSimpleName)

    val fmt = JsonPrintOptions(noNullMembers = true,pretty = false)
    val bao = new ByteArrayOutputStream(8192)
    val ps = new PrintStream(bao)

    var nRec=0
    var nEntries = 0
    var lastReceiveDate = DateTime.UndefinedDateTime

    def startEntry(): Unit = {
      nRec = 0
      bao.reset()
      ps.println("""{"data":[""")
    }
    def finishEntry(): Unit = {
      ps.print(s"""\n],"count":$nRec}""")
      ps.flush()
      writer.write(lastReceiveDate, bao.toByteArray)
      nEntries += 1
    }

    startEntry()

    recs.foreach { rec=>
      val receiveDate = DateTime.ofEpochSeconds(timeStamp(rec)) + dt // we obviously just make this up
      if (receiveDate.timeSince(startDate) > queryInterval) {
        finishEntry()
        startEntry()
        startDate = receiveDate
      }

      if (nRec > 0) ps.println(",")
      rec.printOn(ps,fmt)
      nRec += 1
      lastReceiveDate = receiveDate
    }

    if (nRec > 0)finishEntry()
    writer.close()

    println(s"${recs.length} records written to $nEntries entries in archive $outFile")
  }
}
