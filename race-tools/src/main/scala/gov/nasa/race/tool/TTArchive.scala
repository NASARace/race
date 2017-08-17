/*
 * Copyright (c) 2017, United States Government, as represented by the
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

import java.io.File

import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.track.avro.TrackPoint
import gov.nasa.race.util.{ConsoleIO, DateTimeUtils, FileUtils}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import org.apache.avro.file.{DataFileReader, DataFileWriter}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericRecord}
import gov.nasa.race.track.avro.{TrackPoint => AvroTrackPoint}
import org.apache.avro.Schema
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter, SpecificRecord}

import scala.collection.mutable
import scala.collection.mutable.{SortedSet => MSortedSet}


/**
  * tool to analyze and transform Apache Avro archives with ThreadedTrack data
  */
object TTArchive {

  /** supported operations (set by command line options) */
  object Op extends Enumeration {
    val List, Translate = Value
  }

  /** command line options and arguments */
  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var op: Op.Value = Op.List
    var showHours: Boolean = false
    var maxPartEntries: Int = 100000
    var fullTP: Boolean = false
    var file: Option[File] = None

    opt0("-l", "--list")("list contents of archive (default)") {
      op = Op.List
    }
    opt0("-t", "--translate")("translate into flat ArchivedTrackPointDetails archive") {
      op = Op.Translate
    }

    opt0("--full")(s"create FullTrackPoints, including derivates (default = $fullTP)") {
      fullTP = true
    }
    opt1("--partition")("maxEntries",
      s"max number of entries for temporary translation partitions (default = $maxPartEntries)") { a => maxPartEntries = parseInt (a) }
    opt0("--hours")(s"list number of ThreadedTracks and TrackPoints per hour (default = $showHours)") {
      showHours = true
    }
    requiredArg1("<pathName>", "ThreadedTrack Avro archive to read") { a => file = parseExistingFileOption(a) }
  }

  //--- archive statistics
  var nRecords: Int = 0
  var nTrackPoints: Long = 0
  var nTPmax = 0 // max trackpoints per record
  var tMin: Long = Long.MaxValue // earliest trackpoint time
  var tMax: Long = Long.MinValue // latest trackpoint time
  var maxDur: Long = 0 // maximum flight duration

  val partDir = new File("_part") // directory where we store temporary TrackPoint partitions


  def main(args: Array[String]): Unit = {
    val opts = CliArgs(args) {
      new Opts
    }.getOrElse {
      return
    }

    ifSome(opts.file) { file =>
      opts.op match {
        case Op.List => listArchive(file, opts.showHours)
        case Op.Translate => translateArchive(file, opts.maxPartEntries, opts.fullTP)
      }
    }
  }


  def getArchiveStatistics(dfr: DataFileReader[GenericRecord]) = {
    var rec: GenericRecord = null

    println("collecting archive statistics..")
    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nRecords += 1

      ConsoleIO.line(s"reading record: $nRecords")
      val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
      val nTP = ttps.size

      nTrackPoints += nTP
      if (nTP > nTPmax) nTPmax = nTP

      val t0 = ttps.get(0).get("time").asInstanceOf[java.lang.Long]
      if (t0 < tMin) tMin = t0
      val t1 = ttps.get(ttps.size - 1).get("time").asInstanceOf[java.lang.Long]
      if (t1 > tMax) tMax = t1
      val td = t1 - t0
      if (td > maxDur) maxDur = td
    }
  }

  @inline def getString(rec: GenericRecord, field: String): String = rec.get(field).toString

  @inline def getLong(rec: GenericRecord, field: String): Long = rec.get(field).asInstanceOf[Long]

  @inline def getDouble(rec: GenericRecord, field: String): Double = rec.get(field).asInstanceOf[Double]

  //--- supported operations

  def listArchive(file: File, showHours: Boolean) = {
    println(s"size of archive: ${FileUtils.sizeString(file.length)}")

    val dfr = new DataFileReader(file, new GenericDatumReader[GenericRecord])
    getArchiveStatistics(dfr)
    dfr.close

    ConsoleIO.clearLine
    println(s"number of records:      $nRecords")
    println(s"number of track points: $nTrackPoints")
    println(s"max TP per flight:      $nTPmax")
    println(s"avg TP per flight:      ${nTrackPoints / nRecords}")
    println(s"start date:             ${DateTimeUtils.toSimpleDhmsStringZ(tMin)}")
    println(s"end date:               ${DateTimeUtils.toSimpleDhmsStringZ(tMax)}")
    println(f"duration:               ${DateTimeUtils.hours(tMax - tMin)}%.1fh")
    println(f"max flight duration:    ${DateTimeUtils.hours(maxDur)}%.1fh")
  }

  //--- minimal TrackPoint support (only has part of the TT record information)

  def createAvroTrackPoint(id: String, rec: GenericRecord): AvroTrackPoint = {
    val t = getLong(rec, "time")
    val lat = getDouble(rec, "latitude")
    val lon = getDouble(rec, "longitude")
    val altFt = getDouble(rec, "altitude")
    val hdg = getDouble(rec, "track_heading")
    val spdKn = getDouble(rec, "ground_speed")

    new AvroTrackPoint(id, t, lat, lon, Feet(altFt).toMeters, Knots(spdKn).toMetersPerSecond, hdg)
  }

  object AvroTrackPointOrdering extends Ordering[AvroTrackPoint] {
    def compare(a: AvroTrackPoint, b: AvroTrackPoint) = {
      val dt = a.getDate - b.getDate
      if (dt != 0) dt.toInt
      else {
        val idOrd = a.getId.toString.compareTo(b.getId.toString)
        if (idOrd != 0) idOrd
        else { // dis-ambiguate duplicates
          a.hashCode - b.hashCode
        }
      }
    }
  }

  //--- full TrackPoint (preserving most of the TT record info) - TBD

  //--- generic translation

  class PartitionOrdering[T <: SpecificRecord] (val recOrdering: Ordering[T]) extends Ordering[Partition[T]] {
    def compare (a: Partition[T], b: Partition[T]) = recOrdering.compare(a.rec, b.rec)
  }

  class Partition[T <: SpecificRecord] (val file: File) {

    var dfr: DataFileReader[T] = new DataFileReader(file,new SpecificDatumReader[T])
    var rec: T = dfr.next

    def readNext: Boolean = {
      if (dfr.hasNext) {
        rec = dfr.next(rec)
        true
      } else {
        dfr.close
        rec = null.asInstanceOf[T]
        false
      }
    }
  }

  def writePartition[T <: SpecificRecord](nPart: Int, schema: Schema, tps: MSortedSet[T]): Partition[T] = {
    val partFile = new File(partDir, s"p_$nPart.avro")
    val dfw = new DataFileWriter[T](new SpecificDatumWriter(schema))
    dfw.create(schema,partFile)
    tps.foreach(dfw.append)
    dfw.close

    new Partition(partFile)
  }

  def createPartitions[T <: SpecificRecord](file: File, maxPartEntries: Int,
                          schema: Schema, createTP: (String,GenericRecord)=>T, ordering: Ordering[T]): MSortedSet[Partition[T]] = {
    println("creating temporary partitions...")
    val dfr = new DataFileReader(file, new GenericDatumReader[GenericRecord])

    val tps = MSortedSet[T]()(ordering)

    var rec: GenericRecord = null
    var i = 0
    val partitions = MSortedSet[Partition[T]]()(new PartitionOrdering(ordering))

    while (dfr.hasNext) {
      rec = dfr.next(rec)
      i += 1
      ConsoleIO.line(s"processing track: $i")

      val id = rec.get("tt_id").toString
      val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
      val it = ttps.iterator
      while (it.hasNext) {
        val tp = createTP(id, it.next)
        tps.add(tp)
        if (tps.size >= maxPartEntries) {
          partitions.add(writePartition(partitions.size, schema, tps))
          tps.clear
        }
      }
    }
    ConsoleIO.clearLine
    println(s"number of partitions created: ${partitions.size}")
    partitions
  }

  def mergePartitions[T <: SpecificRecord] (partitions: Seq[Partition[T]], outFile: File) = {

  }

  def translateArchive (file: File, maxPartEntries: Int, useFullTP: Boolean) = {
    if (partDir.isDirectory) FileUtils.deleteRecursively(partDir)
    partDir.mkdir

    if (useFullTP) {
      // not yet
    } else {
      val partitions = createPartitions(file, maxPartEntries,
                                        AvroTrackPoint.getClassSchema, createAvroTrackPoint, AvroTrackPointOrdering)
    }
  }
}
