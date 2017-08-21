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

import java.io.{File, FileOutputStream, PrintStream}

import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.{ConsoleIO, DateTimeUtils, FileUtils}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.common.ManagedResource._
import gov.nasa.race.track.avro.{TrackPoint => AvroTrackPoint}

import org.apache.avro.file.{DataFileReader, DataFileWriter}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericRecord}
import org.apache.avro.Schema
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter, SpecificRecord}

import scala.collection.mutable
import scala.collection.mutable.{SortedSet => MSortedSet}
import scala.util.matching.Regex


/**
  * tool to analyze and transform Apache Avro archives with Threaded Track or TrackPoint data
  */
object TrackTool {

  object ArchType extends Enumeration {
    val ThreadedTrack, TrackPoint, Unknown = Value
  }

  /** supported operations (set by command line options) */
  object Op extends Enumeration {
    val Analyze, Filter, Flatten = Value
  }

  /** command line options and arguments */
  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var op: Op.Value = Op.Analyze

    var maxPartEntries: Int = 100000
    var idPatterns: Seq[Regex] = Seq.empty  // option for Extract/Drop
    var startTime: Long = Long.MinValue // option for Extract/Drop
    var endTime: Long = Long.MaxValue // option for Extract/Drop
    var fullTP: Boolean = false
    var generateId: Boolean = false

    var inFile: Option[File] = None
    var outDir: File = new File("tmp")
    var outFile: File = new File(outDir,"trackpoints.avro")

    //--- the basic operation options
    opt0("-a", "--analyze")("analyze contents of archive (default)") {
      op = Op.Analyze
    }
    opt0("-f", "--filter")("extract records from archive (needs '--id' or '--period' option)") {
      op = Op.Filter
    }
    opt0("-f", "--flatten")("translate into time sorted, flat list of all track points") {
      op = Op.Flatten
    }

    opt1("--id")("<regex>", "regular expression for track ids to extract or drop") { s =>
      idPatterns = idPatterns :+ new Regex(s)
    }
    opt1("--start-time")("<timespec>","start time for track points to extract or drop") { s=>
      startTime = parseTimeMillis(s)
    }
    opt1("--end-time")("<timespec>","end time for track points to extract or drop") { s=>
      endTime = parseTimeMillis(s)
    }

    opt1("--dir")("<pathName>", s"directory for output files (default = $outDir)") { pn=>
      outDir = new File(pn)
    }
    opt1("-o", "--out")("<pathName>", s"pathname of flat archive to create (default = $outFile)") { pn=>
      outFile = new File(pn)
    }
    opt0("--generate-id")(s"generate track id (default = $generateId)") {
      generateId = true
    }
    opt0("--full")(s"create FullTrackPoints, including derivates (default = $fullTP)") {
      fullTP = true
    }
    opt1("--partition")("<maxEntries>",
      s"max number of entries for temporary translation partitions (default = $maxPartEntries)") { a =>
      maxPartEntries = parseInt (a)
    }

    requiredArg1("<pathName>", "ThreadedTrack Avro archive to read") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  //--- names of known schemas
  final val ThreadedTrackName = "ThreadedTrack"
  final val TrackPointName = "TrackPoint"
  final val FullTrackPointName = "FullTrackPoint"

  //--- general archive statistics
  var nRecords: Int = 0
  var nTrackPoints: Int = 0
  var nTPmax = 0 // max track points per track
  var nTPavg = 0 // average number of track points per track
  var tMin: Long = Long.MaxValue // earliest trackpoint time
  var tMax: Long = Long.MinValue // latest trackpoint time
  var maxDur: Long = 0 // maximum flight duration


  def main(args: Array[String]): Unit = {
    if (opts.parse(args)) {
      ifSome(opts.inFile) { file =>
        opts.op match {
          case Op.Analyze => analyzeArchive(file)
          case Op.Flatten => flattenArchive(file, opts.maxPartEntries, opts.fullTP,
            opts.outDir, opts.outFile)
        }
      }
    }
  }

  def checkTime (rec: GenericRecord, fieldName: String): Long = {
    val t = rec.get(fieldName).asInstanceOf[Long]
    if (t < tMin) tMin = t
    if (t > tMax) tMax = t
    t
  }

  @inline def getString(rec: GenericRecord, field: String): String = rec.get(field).toString

  @inline def getBoolean(rec: GenericRecord, field: String): Boolean = rec.get(field).asInstanceOf[Boolean]

  @inline def getInt(rec: GenericRecord, field: String): Int = rec.get(field).asInstanceOf[Int]

  @inline def getLong(rec: GenericRecord, field: String): Long = rec.get(field).asInstanceOf[Long]

  @inline def getDouble(rec: GenericRecord, field: String): Double = rec.get(field).asInstanceOf[Double]

  //--- Check operation - analyze archive contents

  def analyzeArchive(file: File) = {
    println(s"size of archive: ${FileUtils.sizeString(file.length)}")

    for ( dfr <- ensureClose(new DataFileReader(file, new GenericDatumReader[GenericRecord]))){
      dfr.getSchema.getName match {
        case ThreadedTrackName => analyzeTTArchive(dfr)
        case TrackPointName | FullTrackPointName => analyzeFlatArchive(dfr)
        case other => println(s"unknown achive type: $other")
      }
    }
  }

  /** check TrackPoint (flat) archive */
  def analyzeFlatArchive(dfr: DataFileReader[GenericRecord]) = {
    var rec: GenericRecord = null

    // since flat archives store only track points, we cannot have empty tracks
    var reusedIDs = MSortedSet.empty[String] //  multiple tracks using the same id
    var outOfOrderTPs = MSortedSet.empty[String] // track ids with out-of-order track points
    var duplicatedTPs = MSortedSet.empty[String] // track ids wirh duplicated track points
    var ambiguousTPs =  MSortedSet.empty[String] // track ids wirh ambiguous track points

    case class TrackEntry (var date: Long, var completed: Boolean, var nTP: Int, var lat: Double, var lon: Double, var alt: Double)
    val trackMap = mutable.HashMap.empty[String,TrackEntry]

    println("collecting TrackPoint archive statistics..")
    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nRecords += 1

      if (nRecords % 100 == 0) ConsoleIO.line(s"reading record: $nRecords")
      val id = getString(rec,"id")
      val date = checkTime(rec,"date")
      val nTP = getInt(rec,"pointnum")
      val completed = getBoolean(rec,"completed")
      val lat = getDouble(rec,"latitude")
      val lon = getDouble(rec,"longitude")
      val alt = getDouble(rec,"altitude")

      trackMap.get(id) match {
        case Some(e) =>
          if (e.completed && !completed) reusedIDs.add(id) // re-used track ID
          if (date < e.date) { // cannot happen if TrackTool generated archive out of ThreadedTracks
            outOfOrderTPs.add(id)
          } else {
            if (date == e.date) { // duplicate or ambiguous
              if (lat == e.lat && lon == e.lon && alt == e.alt) duplicatedTPs.add(id) else ambiguousTPs.add(id)
            }

            e.date = date
            e.completed = completed
            e.nTP = nTP
            e.lat = lat
            e.lon = lon
            e.alt = alt
          }

        case None => trackMap += id -> TrackEntry(date,completed,nTP,lat,lon,alt)
      }
    }

    var nmax = 0
    for (track <-trackMap.valuesIterator) {
      if (track.nTP > nmax) nmax = track.nTP
    }
    nTPmax = nmax+1 // nTP is 0 based
    nTPavg = nRecords / trackMap.size
    nTrackPoints = nRecords // no difference here

    ConsoleIO.clearLine
    reportNominals
    reportAnomaly("reused record ids:             ", reusedIDs,"reused-ids")
    reportAnomaly("records with duplicated TPs:   ", duplicatedTPs,"duplicate-tp-ids")
    reportAnomaly("records with out-of-order TPs: ", outOfOrderTPs,"order-tp-ids")
    reportAnomaly("records with ambiguous TPs:    ", ambiguousTPs,"ambiguous-tp-ids")
  }

  /** check ThreadedTrack (non-flat) archive */
  def analyzeTTArchive(dfr: DataFileReader[GenericRecord]) = {
    var rec: GenericRecord = null
    val idSet = new mutable.HashSet[String]()

    val reusedIDs = MSortedSet.empty[String] //  multiple ThreadedTracks using the same tt_id
    val emptyIDs = MSortedSet.empty[String] // Threaded Tracks without track points

    val outOfOrderTPs = MSortedSet.empty[String] // track ids with out-of-order track points
    val duplicatedTPs = MSortedSet.empty[String] // track ids wirh duplicated track points
    val ambiguousTPs =  MSortedSet.empty[String] // track ids wirh ambiguous track points

    println("collecting ThreadedTrack archive statistics..")
    while (dfr.hasNext) {
      rec = dfr.next(rec)

      nRecords += 1
      if (nRecords % 10 == 0) ConsoleIO.line(s"reading record: $nRecords")

      val id = rec.get("tt_id").toString
      if (!idSet.add(id)) reusedIDs.add(id)

      val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
      val nTP = ttps.size
      nTrackPoints += nTP
      if (nTP > nTPmax) nTPmax = nTP

      val t0 = checkTime(ttps.get(0),"time")
      val t1 = checkTime(ttps.get(ttps.size - 1), "time")
      val td = t1 - t0
      if (td > maxDur) maxDur = td

      if (nTP > 0) {
        var nDupTP = 0 // duplicated track points
        var nAmbigTP = 0 // ambiguous track points
        var nOrderTP = 0 // out of order track points

        val it = ttps.iterator
        var tpLast = it.next
        var tLast = t0
        var hcLast = tpLast.hashCode

        while (it.hasNext) {
          val tp = it.next
          val t = tp.get("time").asInstanceOf[Long]
          val hc = tp.hashCode

          if (t < tLast) {
            nOrderTP += 1
          } else if (t == tLast) {
            if (hc == hcLast) nDupTP += 1 else nAmbigTP += 1 // should be either-or if hashCode() is correct
          }

          tpLast = tp
          tLast = t
          hcLast = hc
        }

        if (nDupTP > 0) duplicatedTPs.add(id)
        if (nAmbigTP > 0) ambiguousTPs.add(id)
        if (nOrderTP > 0) outOfOrderTPs.add(id)
      } else {
        emptyIDs.add(id)
      }
    }

    nTPavg = nTrackPoints / nRecords

    ConsoleIO.clearLine
    reportNominals
    reportAnomaly("empty records:                 ", emptyIDs,"empty-ids")
    reportAnomaly("reused record ids:             ", reusedIDs,"reused-ids")
    reportAnomaly("records with duplicated TPs:   ", duplicatedTPs,"duplicate-tp-ids")
    reportAnomaly("records with out-of-order TPs: ", outOfOrderTPs,"order-tp-ids")
    reportAnomaly("records with ambiguous TPs:    ", ambiguousTPs,"ambiguous-tp-ids")
  }

  def reportAnomaly (msg: String, ids: Iterable[String], pathName: String) = {
    if (ids.nonEmpty) {
      val idFile = new File(opts.outDir, pathName)
      val ps = new PrintStream(new FileOutputStream(idFile))
      ids.foreach(ps.println)
      ps.close

      print(msg)
      println(s" ${ids.size} (ids saved to $idFile)")
    }
  }

  // this applies to both TT and TrackPoint archives
  def reportNominals = {
    println(s"number of records:      $nRecords")
    println(s"number of track points: $nTrackPoints")
    println(s"max TP per flight:      $nTPmax")
    println(s"avg TP per flight:      $nTPavg")
    println(s"start date:             ${DateTimeUtils.toSimpleDhmsStringZ(tMin)}")
    println(s"end date:               ${DateTimeUtils.toSimpleDhmsStringZ(tMax)}")
    println(f"duration:               ${DateTimeUtils.hours(tMax - tMin)}%.1fh")
    println(f"max flight duration:    ${DateTimeUtils.hours(maxDur)}%.1fh")
  }


  //--- Flatten operation - support for flattening of ThreadedTrack archives


  //--- minimal TrackPoint support (only has part of the TT record information)

  def createAvroTrackPoint(id: String, rec: GenericRecord, isCompleted: Boolean, pointNum: Int): AvroTrackPoint = {
    val t = getLong(rec, "time")
    val lat = getDouble(rec, "latitude")
    val lon = getDouble(rec, "longitude")
    val altFt = getDouble(rec, "altitude")
    val hdg = getDouble(rec, "track_heading")
    val spdKn = getDouble(rec, "ground_speed")

    // do some on-the-fly statistics
    if (t < tMin) tMin = t
    if (t > tMax) tMax = t

    new AvroTrackPoint(id, t, lat, lon,
                       Feet(altFt).toMeters, Knots(spdKn).toMetersPerSecond, hdg,
                       isCompleted,pointNum)
  }

  object AvroTrackPointOrdering extends Ordering[AvroTrackPoint] {
    def compare(a: AvroTrackPoint, b: AvroTrackPoint): Int = {
      val dt = a.getDate - b.getDate
      if (dt != 0) dt.toInt
      else {
        val idOrd = a.getId.toString.compareTo(b.getId.toString)
        if (idOrd != 0) idOrd
        else { // dis-ambiguate ambiguities and duplicates, which we want to preserve
          System.identityHashCode(a) - System.identityHashCode(b)
        }
      }
    }
  }

  //--- full TrackPoint (preserving most of the TT record info) - TBD


  class PartitionOrdering[T <: SpecificRecord] (val recOrdering: Ordering[T]) extends Ordering[Partition[T]] {
    def compare (a: Partition[T], b: Partition[T]) = recOrdering.compare(a.rec, b.rec)
  }

  class Partition[T <: SpecificRecord] (val file: File) {

    var dfr: DataFileReader[T] = new DataFileReader(file,new SpecificDatumReader[T])
    var rec: T = dfr.next
    assert(rec!= null)

    def readNext: Boolean = {
      if (dfr.hasNext) {
        rec = dfr.next(rec)
        true
      } else {
        dfr.close
        rec = null.asInstanceOf[T]
        file.delete() // no need to keep partition archive on disk
        false
      }
    }
  }

  def writePartition[T <: SpecificRecord](tmpDir: File, nPart: Int, schema: Schema, tps: MSortedSet[T]): Partition[T] = {
    val partFile = new File(tmpDir, s"p_$nPart.avro")
    val dfw = new DataFileWriter[T](new SpecificDatumWriter(schema))
    dfw.create(schema,partFile)
    tps.foreach(dfw.append)
    dfw.close

    new Partition(partFile)
  }

  var numberOfGeneratedTracks: Int = 0

  def getTTPId (rec: GenericRecord): String = {
    numberOfGeneratedTracks += 1
    rec.get("tt_id").asInstanceOf[CharSequence].subSequence(8,16).toString
  }

  def getGeneratedId (rec: GenericRecord): String = {
    numberOfGeneratedTracks += 1
    numberOfGeneratedTracks.toString
  }


  def createPartitions[T <: SpecificRecord](file: File, maxPartEntries: Int, schema: Schema,
                                            createTP: (String,GenericRecord,Boolean,Int)=>T,
                                            ordering: Ordering[T]): MSortedSet[Partition[T]] = {
    println("creating temporary partitions...")
    val partitions = MSortedSet[Partition[T]]()(new PartitionOrdering(ordering))
    val dfr = new DataFileReader(file, new GenericDatumReader[GenericRecord])
    val tps = MSortedSet[T]()(ordering)
    var rec: GenericRecord = null
    var nTracks = 0
    var nTP: Long = 0

    val getId: (GenericRecord)=>String = if (opts.generateId) getGeneratedId else getTTPId

    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nTracks += 1
      if (nTracks % 10 == 0) ConsoleIO.line(s"processing track: $nTracks, partition: ${partitions.size}")

      val id = getId(rec)
      val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
      nTP += ttps.size()

      var i = 0 // running point number (we could use both TTP or TP)
      val it = ttps.iterator
      while (it.hasNext) {
        val tp = createTP(id, it.next, it.hasNext, i)
        if (!tps.add(tp)) {
          // should not happen since we store the running tp number to disambiguate / keep duplicates
          println(s"[WARNING] duplicated track point entry $i ignored: $tp")
        } else {
          i += 1
          if (tps.size >= maxPartEntries) {
            partitions.add(writePartition(opts.outDir, partitions.size, schema, tps))
            tps.clear
          }
        }
      }
    }
    if (tps.nonEmpty) partitions.add(writePartition(opts.outDir,partitions.size, schema, tps))

    ConsoleIO.clearLine
    println(s"number of partitions: ${partitions.size} / $nTP track points")
    partitions
  }

  def mergePartitions[T <: SpecificRecord] (partitions: MSortedSet[Partition[T]], outFile: File, schema: Schema) = {
    val dfw = new DataFileWriter[T](new SpecificDatumWriter(schema))
    dfw.create(schema,outFile)

    var rec: T = null.asInstanceOf[T]
    var nTP: Long = 0

    println(s"merging ${partitions.size} partitions into $outFile ...")
    while (partitions.nonEmpty) {
      val p = partitions.head
      rec = p.rec
      partitions.remove(p)
      if (rec != null) {
        dfw.append(p.rec)
        nTP += 1
        if (nTP % 1000 == 0) ConsoleIO.line(s"processing track point: $nTP")

        if (p.readNext) partitions.add(p) // re- sort in the partition according to next record
      }
    }
    dfw.close

    ConsoleIO.clearLine
    println(s"size of generated $outFile: ${FileUtils.sizeString(outFile.length)}")
    println(s"number of track points: $nTP")
    println(s"start date:             ${DateTimeUtils.toSimpleDhmsStringZ(tMin)}")
    println(s"end date:               ${DateTimeUtils.toSimpleDhmsStringZ(tMax)}")
    println(f"duration:               ${DateTimeUtils.hours(tMax - tMin)}%.1fh")
  }

  def flattenArchive(inFile: File, maxPartEntries: Int, useFullTP: Boolean, tmpDir: File, outFile: File) = {
    if (!tmpDir.isDirectory) tmpDir.mkdir
    if (outFile.isFile) outFile.delete

    if (useFullTP) {
      // not yet
    } else {
      val schema = AvroTrackPoint.getClassSchema
      val partitions = createPartitions(inFile, maxPartEntries,schema,createAvroTrackPoint,AvroTrackPointOrdering)
      mergePartitions(partitions,outFile,schema)
    }
  }
}
