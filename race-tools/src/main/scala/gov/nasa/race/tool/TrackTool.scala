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
import gov.nasa.race.util.{ConsoleIO, FileUtils}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Acceleration._
import gov.nasa.race.common.ManagedResource._
import gov.nasa.race.track.avro.{FullTrackPoint => AvroFullTrackPoint, TrackIdRecord => AvroTrackIdRecord, TrackInfoRecord => AvroTrackInfo, TrackPoint => AvroTrackPoint, TrackRoutePoint => AvroTrackRoutePoint}
import gov.nasa.race.uom.DateTime
import org.apache.avro.file.{DataFileReader, DataFileWriter}
import org.apache.avro.generic.{GenericData, GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.Schema
import org.apache.avro.specific.{SpecificDatumReader, SpecificDatumWriter, SpecificRecord}

import scala.collection.mutable.{HashMap => MHashMap, HashSet => MHashSet, SortedSet => MSortedSet}
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex


/**
  * tool to analyze and transform Apache Avro archives with Threaded Track or TrackPoint data
  *
  * TrackTool supports four major operations:
  *   - flatten Threaded Track archives into TrackPoint archives (which can be replayed in constant heap space)
  *   - analyze existing Threaded Track and TrackPoint archives (statistics and generic anomalies)
  *   - create filtered Threaded Track and TrackPoint archives
  *   - list contents of archives
  *
  * All functions support filtering of input (id and/or time at this point)
  */
object TrackTool {

  object ArchType extends Enumeration {
    val ThreadedTrack, TrackPoint, ThreadedFlight, TrackInfo, Unknown = Value
  }

  /** supported operations (set by command line options) */
  object Op extends Enumeration {
    val Analyze, Filter, Flatten, List = Value
  }

  /** command line options and arguments */
  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var op: Op.Value = Op.Analyze

    var maxPartEntries: Int = 100000
    var excludePatterns: Seq[Regex] = Seq.empty // option for Filter
    var includePatterns: Seq[Regex] = Seq.empty
    var startTime: Long = Long.MinValue // option for Extract/Drop
    var endTime: Long = Long.MaxValue // option for Extract/Drop
    var full: Boolean = false // create long TrackPoint / TrackInfo records
    var generateId: Boolean = false

    var inFile: Option[File] = None
    var outDir: File = new File("tmp")
    var outFile: Option[File] = None

    //--- the basic operation options
    opt0("-a", "--analyze")("analyze contents of archive (default)") {
      op = Op.Analyze
    }
    opt0("--filter")("extract records from archive (needs include/exclude and/or start-/end-time options)") {
      op = Op.Filter
    }
    opt0("-f", "--flatten")("translate ThreadedTracks into time sorted, flat list of TrackPoints") {
      op = Op.Flatten
    }
    opt0("-l", "--list")("list (optionally filtered) contents of archive") {
      op = Op.List
    }

    opt1("--exclude")("<regex>[,...]", "regular expression(s) for track ids to exclude") { s =>
      excludePatterns = s.split(',').map(new Regex(_)) ++: excludePatterns
    }
    opt1("--exclude-from")("<pathName>", "file with regular expression(s) for track ids to exclude") { s =>
      excludePatterns = FileUtils.getLines(s).map(new Regex(_)) ++: excludePatterns
    }
    opt1("--include")("<regex>[,...]", "regular expression(s) for track ids to include") { s =>
      includePatterns = s.split(',').map(new Regex(_)) ++: includePatterns
    }
    opt1("--include-from")("<pathName>", "file with regular expression(s) for track ids to include") { s =>
      includePatterns = FileUtils.getLines(s).map(new Regex(_)) ++: includePatterns
    }
    opt1("--start-time")("<timespec>", "start time for track points to extract") { s =>
      startTime = parseTimeMillis(s)
    }
    opt1("--end-time")("<timespec>", "end time for track points to extract") { s =>
      endTime = parseTimeMillis(s)
    }

    opt1("--dir")("<pathName>", s"directory for output files (default = $outDir)") { pn =>
      outDir = new File(pn)
    }
    opt1("-o", "--out")("<pathName>", s"optional pathname of flat archive to create (default = $outFile)") { pn =>
      outFile = Some(new File(pn))
    }
    opt0("--generate-id")(s"generate track id (default = $generateId)") {
      generateId = true
    }
    opt0("--full")(s"create long TrackPoint or FlightInfo records (default = $full)") {
      full = true
    }
    opt1("--partition")("<maxEntries>",
      s"max number of entries for temporary flatten partitions (default = $maxPartEntries)") { a =>
      maxPartEntries = parseInt(a)
    }

    requiredArg1("<pathName>", "Avro archive to read (either ThreadedTracks or flat TrackPoints)") { a =>
      inFile = parseExistingFileOption(a)
    }
  }

  var opts = new Opts

  //--- names of known schemas
  final val ThreadedFlightName = "ThreadedFlight"
  final val TrackInfoName = "TrackInfoRecord"
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

  def main(args: Array[String]): Unit = {
    if (opts.parse(args)) {
      ifSome(opts.inFile) { file =>
        opts.op match {
          case Op.Analyze => analyzeArchive(file)
          case Op.Flatten => flattenArchive(file)
          case Op.Filter => filterArchive(file)
          case Op.List => listArchive(file)
        }
      }
    }
  }

  @inline def checkTime(t: Long): Long = {
    if (t < tMin) tMin = t
    if (t > tMax) tMax = t
    t
  }

  @inline def getCheckedTime(rec: GenericRecord, fieldName: String): Long = checkTime(getLong(rec,fieldName))

  @inline def getString(rec: GenericRecord, field: String): String = rec.get(field).toString

  def getStringOrElse(rec: GenericRecord, field: String, defaultValue: String): String = {
    val v = rec.get(field)
    if (v != null) v.toString else defaultValue
  }

  @inline def getBoolean(rec: GenericRecord, field: String): Boolean = rec.get(field).asInstanceOf[Boolean]

  @inline def getInt(rec: GenericRecord, field: String): Int = rec.get(field).asInstanceOf[Int]

  @inline def getLong(rec: GenericRecord, field: String): Long = rec.get(field).asInstanceOf[Long]

  @inline def getDouble(rec: GenericRecord, field: String): Double = rec.get(field).asInstanceOf[Double]

  def getOptionalDouble(rec: GenericRecord, field: String): Double = {
    val v = rec.get(field)
    if (v == null) Double.NaN else v.asInstanceOf[Double]
  }

  def isRelevantId(id: String): Boolean = {
    val isNotExcluded = !opts.excludePatterns.exists(_.findFirstIn(id).isDefined)
    val isIncluded = opts.includePatterns.isEmpty || opts.includePatterns.exists(_.findFirstIn(id).isDefined)

    isIncluded && isNotExcluded
  }

  def timeFilteredTTPs(ttps: GenericData.Array[GenericRecord]): GenericData.Array[GenericRecord] = {
    val startTime = opts.startTime
    val endTime = opts.endTime

    val t0 = getCheckedTime(ttps.get(0), "time")
    val t1 = getCheckedTime(ttps.get(ttps.size - 1), "time")

    if (t0 > endTime || t1 < startTime) { // no overlap, completely outside time window
      new GenericData.Array[GenericRecord](0, ttps.getSchema)
    } else {
      if (t0 >= startTime && t1 <= endTime) { // completely included, no need for new array
        ttps
      } else { // we need to filter
        val newTTPS = new GenericData.Array[GenericRecord](0, ttps.getSchema)
        val it = ttps.iterator
        while (it.hasNext) {
          val rec = it.next()
          val t = getLong(rec, "time")
          if (t >= startTime && t <= endTime) newTTPS.add(rec)
        }
        newTTPS
      }
    }
  }

  @inline final def isRelevantTime(t: Long) = t >= opts.startTime && t <= opts.endTime

  @inline final def isRelevantTPtime(rec: GenericRecord): Boolean = isRelevantTime(getLong(rec,"date"))

  def isRelevantFlatRec(rec: GenericRecord): Boolean = {
    isRelevantId(rec.get("id").toString) && isRelevantTPtime(rec)
  }

  def processFilteredFlatArchive (dfr: DataFileReader[GenericRecord])(f: GenericRecord=>Unit) = {
    var rec: GenericRecord = null
    var nRecRead = 0

    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nRecRead += 1

      if (isRelevantFlatRec(rec)){
        nRecords += 1
        nTrackPoints += 1

        f(rec)
      }
    }
    dfr.close
  }

  def getOutFile (defaultName: String): File = {
    opts.outFile.getOrElse {
      val f = new File(defaultName)
      if (f.getParent == null) new File(opts.outDir,defaultName) else f
    }
  }

  def ensureEmptyOutFile (defaultName: String): Option[File] = FileUtils.ensureEmptyWritable(getOutFile(defaultName))

  //--- Check operation - analyze archive contents

  def analyzeArchive(file: File) = {
    println(s"size of archive: ${FileUtils.sizeString(file.length)}")

    for (dfr <- ensureClose(new DataFileReader(file, new GenericDatumReader[GenericRecord]))) {
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
    var ambiguousTPs = MSortedSet.empty[String] // track ids wirh ambiguous track points

    case class TrackEntry(var date: Long, var completed: Boolean, var nTP: Int, var lat: Double, var lon: Double, var alt: Double)
    val trackMap = MHashMap.empty[String, TrackEntry]

    println("analyze TrackPoint archive ..")
    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nRecords += 1
      if (nRecords % 100 == 0) ConsoleIO.line(s"reading record: $nRecords")

      val id = getString(rec, "id")
      if (isRelevantId(id)) {
        val date = getLong(rec,"date")
        if (isRelevantTime(date)) {
          checkTime(date)
          val nTP = getInt(rec, "pointnum")
          val completed = getBoolean(rec, "completed")
          val lat = getDouble(rec, "latitude")
          val lon = getDouble(rec, "longitude")
          val alt = getDouble(rec, "altitude")

          trackMap.get(id) match {
            case Some(e) =>
              if (e.completed && !completed) reusedIDs.add(id) // re-used track ID
              if (date < e.date) { // cannot happen if TrackTool generated archive from ThreadedTracks
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

            case None => trackMap += id -> TrackEntry(date, completed, nTP, lat, lon, alt)
          }
        }
      }
    }

    var nmax = 0
    for (track <- trackMap.valuesIterator) {
      if (track.nTP > nmax) nmax = track.nTP
    }
    nTPmax = nmax + 1 // nTP is 0 based
    nTPavg = nRecords / trackMap.size
    nTrackPoints = nRecords // no difference here

    ConsoleIO.clearLine
    reportNominals(true)
    reportAnomaly("reused record ids:             ", reusedIDs, "reused-ids")
    reportAnomaly("records with duplicated TPs:   ", duplicatedTPs, "duplicate-tp-ids")
    reportAnomaly("records with out-of-order TPs: ", outOfOrderTPs, "order-tp-ids")
    reportAnomaly("records with ambiguous TPs:    ", ambiguousTPs, "ambiguous-tp-ids")
  }

  /** check ThreadedTrack (non-flat) archive */
  def analyzeTTArchive(dfr: DataFileReader[GenericRecord]) = {
    var rec: GenericRecord = null
    val idSet = new MHashSet[String]()

    val reusedIDs = MSortedSet.empty[String] //  multiple ThreadedTracks using the same tt_id
    val emptyIDs = MSortedSet.empty[String] // Threaded Tracks without track points

    val outOfOrderTPs = MSortedSet.empty[String] // track ids with out-of-order track points
    val duplicatedTPs = MSortedSet.empty[String] // track ids wirh duplicated track points
    val ambiguousTPs = MSortedSet.empty[String] // track ids wirh ambiguous track points

    println("analyze ThreadedTrack archive ..")
    while (dfr.hasNext) {
      rec = dfr.next(rec)

      nRecords += 1
      if (nRecords % 10 == 0) ConsoleIO.line(s"reading record: $nRecords")

      val id = getString(rec,"tt_id")
      if (isRelevantId(id)) {
        if (!idSet.add(id)) reusedIDs.add(id)

        val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
        val nTP = ttps.size

        if (nTP > 0) {
          var nDupTP = 0 // duplicated track points
          var nAmbigTP = 0 // ambiguous track points
          var nOrderTP = 0 // out of order track points

          val it = ttps.iterator
          var tLast: Long = 0
          var hcLast = 0
          var n = 0

          while (it.hasNext) {
            val tp = it.next
            val t = getLong(tp, "time")
            if (isRelevantTime(t)) {
              checkTime(t)
              n += 1
              nTrackPoints += 1
              val hc = tp.hashCode

              if (t < tLast) {
                nOrderTP += 1
              } else if (t == tLast) {
                if (hc == hcLast) nDupTP += 1 else nAmbigTP += 1 // should be either-or if hashCode() is correct
              }

              tLast = t
              hcLast = hc
            }
          }
          if (n > nTPmax) nTPmax = n

          if (nDupTP > 0) duplicatedTPs.add(id)
          if (nAmbigTP > 0) ambiguousTPs.add(id)
          if (nOrderTP > 0) outOfOrderTPs.add(id)
        } else {
          emptyIDs.add(id)
        }
      }
    }

    nTPavg = nTrackPoints / nRecords

    ConsoleIO.clearLine
    reportNominals(false)
    reportAnomaly("empty records:                 ", emptyIDs, "empty-ids")
    reportAnomaly("reused record ids:             ", reusedIDs, "reused-ids")
    reportAnomaly("records with duplicated TPs:   ", duplicatedTPs, "duplicate-tp-ids")
    reportAnomaly("records with out-of-order TPs: ", outOfOrderTPs, "order-tp-ids")
    reportAnomaly("records with ambiguous TPs:    ", ambiguousTPs, "ambiguous-tp-ids")
  }

  def reportAnomaly(msg: String, ids: Iterable[String], fileName: String) = {
    if (ids.nonEmpty) {
      ifSome(FileUtils.ensureDir(opts.outDir)) { outDir =>
        val idFile = new File(outDir, fileName)
        val ps = new PrintStream(new FileOutputStream(idFile))
        ids.foreach(ps.println)
        ps.close

        print(msg)
        println(s" ${ids.size} (ids saved to $idFile)")
      } orElse { none(s"[WARNING] could not write $fileName") }
    }
  }

  def reportNominals(isFlatArchive: Boolean) = {
    if (!isFlatArchive) println(s"number of records:      $nRecords")
    println(s"number of track points: $nTrackPoints")
    println(s"max TP per flight:      $nTPmax")
    println(s"avg TP per flight:      $nTPavg")
    println(s"start date:             ${DateTime.epochMillisToString(tMin)}")
    println(s"end date:               ${DateTime.epochMillisToString(tMax)}")
    println(f"duration:               ${DateTime.hoursBetweenEpochMillis(tMin, tMax)}%.1fh")
  }

  def initFlightInfoStore (file: File) = {
    if (file.getName.endsWith(".avro")) {
      val dfr = new DataFileReader(file, new GenericDatumReader[GenericRecord])
      val schema = dfr.getSchema

    }
  }

  //--- Flatten operation - support for flattening of ThreadedTrack archives

  def flattenArchive (inFile: File) = {
    for (dfr <- ensureClose(new DataFileReader(inFile, new GenericDatumReader[GenericRecord]))) {
      dfr.getSchema.getName match {
        case ThreadedTrackName => flattenThreadedTracks(dfr)
        case ThreadedFlightName => if (opts.full) createFullTrackInfo(dfr) else createTrackIdMap(dfr)
        case other => println(s"unknown input archive schema: $other")
      }
    }
  }

  def createTrackIdMap (dfr: DataFileReader[GenericRecord]) = {
    ifSome(ensureEmptyOutFile("trackinfos.avro")) { outFile =>
      val schema = AvroTrackIdRecord.getClassSchema
      for (dfw <- ensureClose(new DataFileWriter[AvroTrackIdRecord](new SpecificDatumWriter(schema)))) {
        dfw.create(schema, outFile)
        var tfRec: GenericRecord = null
        while (dfr.hasNext) {
          tfRec = dfr.next(tfRec)
          val tfDataRec = tfRec.get("threaded_metadata").asInstanceOf[GenericRecord]
          val id = getTTPId(tfRec)
          val cs = getString(tfDataRec, "aircraft_id")
          val tiRec = new AvroTrackIdRecord(id,cs)
          dfw.append(tiRec)
        }
      }
    } orElse {
      none("could not create target archive")
    }
  }

  def createFullTrackInfo(dfr: DataFileReader[GenericRecord]) = {
    val emptyRoute = new java.util.ArrayList[AvroTrackRoutePoint](0)

    ifSome(ensureEmptyOutFile("trackinfos.avro")) { outFile =>
      val schema = AvroTrackInfo.getClassSchema
      for (dfw <- ensureClose(new DataFileWriter[AvroTrackInfo](new SpecificDatumWriter(schema)))) {
        dfw.create(schema, outFile)
        var tfRec: GenericRecord = null
        while (dfr.hasNext) {
          tfRec = dfr.next(tfRec)
          val tfDataRec = tfRec.get("threaded_metadata").asInstanceOf[GenericRecord]

          val id = getTTPId(tfRec)
          val cs = getString(tfDataRec, "aircraft_id")
          val acType = getStringOrElse(tfDataRec, "aircraft_type", "?")
          val departureAirport = getStringOrElse(tfDataRec, "departure_airport", "?")
          val tDep = getLong(tfDataRec, "start_time")
          val arrivalAirport = getStringOrElse(tfDataRec, "arrival_airport", "?")
          val tArr =  getLong(tfDataRec, "end_time")
          val dist = getDouble(tfDataRec, "end_distance")
          // we don't care for the rest, which is also in the ThreadedTrack data

          val tiRec = new AvroTrackInfo(id,cs,"aircraft",acType,
            departureAirport, tDep, arrivalAirport, tArr, emptyRoute)
          dfw.append(tiRec)
        }
      }
    } orElse {
      none("could not create target archive")
    }
  }

  def flattenThreadedTracks (dfr: DataFileReader[GenericRecord]) = {
    ifSome(ensureEmptyOutFile("trackpoints.avro")) { outFile =>
      val tmpDir = outFile.getParentFile

      if (opts.full) {
        val schema = AvroFullTrackPoint.getClassSchema
        val partitions = createPartitions(dfr, tmpDir, opts.maxPartEntries, schema, createFullTrackPoint, AvroFullTrackPointOrdering)
        mergePartitions(partitions, outFile, schema)
      } else {
        val schema = AvroTrackPoint.getClassSchema
        val partitions = createPartitions(dfr, tmpDir, opts.maxPartEntries, schema, createTrackPoint, AvroTrackPointOrdering)
        mergePartitions(partitions, outFile, schema)
      }
    } orElse {
      none("could not create target archive")
    }
  }

  def createPartitions[T <: SpecificRecord](dfr: DataFileReader[GenericRecord], tmpDir: File, maxPartEntries: Int, schema: Schema,
                                            createTP: (String, GenericRecord, Boolean, Int) => T,
                                            ordering: Ordering[T]): MSortedSet[Partition[T]] = {
    println("creating temporary partitions...")
    val partitions = MSortedSet[Partition[T]]()(new PartitionOrdering(ordering))
    val tps = MSortedSet[T]()(ordering)
    var rec: GenericRecord = null
    var nTracks = 0
    var nTP = 0

    val getId: (GenericRecord) => String = if (opts.generateId) getGeneratedId else getTTPId

    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nTracks += 1
      if (nTracks % 10 == 0) ConsoleIO.line(s"processing track: $nTracks, partition: ${partitions.size}")

      val id = getId(rec)
      if (isRelevantId(id)) {
        val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]

        var n = 0
        val it = ttps.iterator
        while (it.hasNext) {
          val ttp = it.next
          val t = getLong(ttp,"time")

          if (isRelevantTime(t)) {
            val tp = createTP(id, ttp, !it.hasNext, n)
            if (!tps.add(tp)) {
              // should not happen since we store the running tp number to disambiguate / keep duplicates
              println(s"[WARNING] duplicated track point entry ignored: $tp")
            } else {
              n += 1
              nTP += 1
              if (tps.size >= maxPartEntries) {
                partitions.add(writePartition(partFile(tmpDir,partitions.size), schema, tps))
                tps.clear()
              }
            }
          }
        }
      }
    }
    if (tps.nonEmpty) partitions.add(writePartition(partFile(tmpDir,partitions.size), schema, tps))

    ConsoleIO.clearLine
    println(s"number of partitions: ${partitions.size} / $nTP track points")
    partitions
  }

  def partFile(tmpDir: File, num: Int) = new File(tmpDir, s"part_$num.avro")

  def writePartition[T <: SpecificRecord](partFile: File, schema: Schema, tps: MSortedSet[T]): Partition[T] = {
    val dfw = new DataFileWriter[T](new SpecificDatumWriter(schema))
    dfw.create(schema, partFile)
    tps.foreach(dfw.append)
    dfw.close

    new Partition(partFile)
  }

  def mergePartitions[T <: SpecificRecord](partitions: MSortedSet[Partition[T]], outFile: File, schema: Schema) = {
    val dfw = new DataFileWriter[T](new SpecificDatumWriter(schema))
    dfw.create(schema, outFile)

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
    println(s"start date:             ${DateTime.epochMillisToString(tMin)}")
    println(s"end date:               ${DateTime.epochMillisToString(tMax)}")
    println(f"duration:               ${DateTime.hoursBetweenEpochMillis(tMin, tMax)}%.1fh")
  }


  //--- minimal TrackPoint support (only has part of the TT record information)

  def createTrackPoint(id: String, rec: GenericRecord, isCompleted: Boolean, pointNum: Int): AvroTrackPoint = {
    val t = getLong(rec, "time")
    val lat = getDouble(rec, "latitude")
    val lon = getDouble(rec, "longitude")
    val altFt = getDouble(rec, "pressure_altitude")
    val hdg = getDouble(rec, "track_heading")
    val spdKn = getDouble(rec, "ground_speed")

    // do some on-the-fly statistics
    if (t < tMin) tMin = t
    if (t > tMax) tMax = t

    new AvroTrackPoint(id, t, lat, lon,
      Feet(altFt).toMeters, Knots(spdKn).toMetersPerSecond, hdg,
      isCompleted, pointNum)
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

  //--- full TrackPoint - unfortunately Avro generated types are not related so we have to duplicate

  def createFullTrackPoint(id: String, rec: GenericRecord, isCompleted: Boolean, pointNum: Int): AvroFullTrackPoint = {
    val t = getLong(rec, "time")
    val lat = getDouble(rec, "latitude")
    val lon = getDouble(rec, "longitude")
    val altFt = getDouble(rec, "altitude")
    val hdg = getDouble(rec, "track_heading")
    val spdKn = getDouble(rec, "ground_speed")
    val distNm = getDouble(rec,"along_track_distance")
    val accelKnPerMin = getOptionalDouble(rec,"ground_acceleration")
    val climbRateFtPerMin = getOptionalDouble(rec,"climb_rate")
    val amendments: String = null
    // TODO - maybe add residuals

    // do some on-the-fly statistics
    if (t < tMin) tMin = t
    if (t > tMax) tMax = t

    new AvroFullTrackPoint(id, t, lat, lon,
      Feet(altFt).toMeters, Knots(spdKn).toMetersPerSecond, hdg,
      NauticalMiles(distNm).toMeters,
      KnotsPerMinute(accelKnPerMin).toMetersPerSecond2,
      FeetPerMinute(climbRateFtPerMin).toMetersPerSecond,
      isCompleted, pointNum, amendments)
  }

  object AvroFullTrackPointOrdering extends Ordering[AvroFullTrackPoint] {
    def compare(a: AvroFullTrackPoint, b: AvroFullTrackPoint): Int = {
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

  //--- flatten helper functions

  class PartitionOrdering[T <: SpecificRecord](val recOrdering: Ordering[T]) extends Ordering[Partition[T]] {
    def compare(a: Partition[T], b: Partition[T]) = recOrdering.compare(a.rec, b.rec)
  }

  class Partition[T <: SpecificRecord](val file: File) {

    var dfr: DataFileReader[T] = new DataFileReader(file, new SpecificDatumReader[T])
    var rec: T = dfr.next
    assert(rec != null)

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

  var numberOfGeneratedTracks: Int = 0

  def getTTPId(rec: GenericRecord): String = {
    numberOfGeneratedTracks += 1
    rec.get("tt_id").asInstanceOf[CharSequence].subSequence(8, 16).toString
  }

  def getGeneratedId(rec: GenericRecord): String = {
    numberOfGeneratedTracks += 1
    numberOfGeneratedTracks.toString
  }

  //--- filter function

  def filterArchive(inFile: File) = {
    for (dfr <- ensureClose(new DataFileReader(inFile, new GenericDatumReader[GenericRecord]));
         dfw <- ensureClose(new DataFileWriter(new GenericDatumWriter[GenericRecord]))) {
      val schema = dfr.getSchema

      schema.getName match {
        case ThreadedTrackName =>
          ifSome(ensureEmptyOutFile("threaded-tracks.avro")){ outFile =>
            println(s"generating ThreadedTrack archive $outFile...")
            dfw.create(schema, outFile)
            filterTTArchive(dfr, dfw)
          }

        case TrackPointName | FullTrackPointName =>
          ifSome(ensureEmptyOutFile("trackpoints.avro")) { outFile =>
            println(s"generating TrackPoint archive $outFile...")
            dfw.create(schema, outFile)
            filterFlatArchive(dfr,dfw)
          }

        case other => println(s"unknown archive type: $other")
      }
    }
  }

  def filterFlatArchive (dfr: DataFileReader[GenericRecord], dfw: DataFileWriter[GenericRecord]) = {
    processFilteredFlatArchive(dfr){ rec=>
      dfw.append(rec)
    }
  }

  def filterTTArchive(dfr: DataFileReader[GenericRecord], dfw: DataFileWriter[GenericRecord]) = {
    var rec: GenericRecord = null
    var nRecRead = 0
    var nTTPSRead = 0

    while (dfr.hasNext) {
      rec = dfr.next(rec)

      nRecRead += 1
      if (nRecRead % 10 == 0) ConsoleIO.line(s"reading record: $nRecRead")

      val id = rec.get("tt_id").toString
      if (isRelevantId(id)) {
        val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
        nTTPSRead += ttps.size

        val ttpsOut = timeFilteredTTPs(ttps)
        if (!ttpsOut.isEmpty) {
          // TODO - add geographic filtering here
          if (ttpsOut.ne(ttps)) rec.put("threaded_track", ttpsOut)
          nRecords += 1
          nTrackPoints += ttpsOut.size
          dfw.append(rec)
        }
      }
    }

    ConsoleIO.clearLine
    println(s"number of records read:         $nRecRead")
    println(s"number of track points read:    $nTTPSRead")
    println(s"number of records written:      $nRecords")
    println(s"number of track points written: $nTrackPoints")
  }

  //--- archive listing

  def listArchive(inFile: File) = {
    for (dfr <- ensureClose(new DataFileReader(inFile, new GenericDatumReader[GenericRecord]))) {
      dfr.getSchema.getName match {
        case ThreadedTrackName => listTTArchive(dfr)
        case TrackPointName | FullTrackPointName => listFlatArchive(dfr)
        case other => println(s"unknown archive type: $other")
      }
    }
  }

  def listTTArchive(dfr: DataFileReader[GenericRecord]) = {
    var rec: GenericRecord = null
    var nRecRead = 0
    var nTTPSRead = 0

    while (dfr.hasNext) {
      rec = dfr.next(rec)
      nRecRead += 1

      val id = rec.get("tt_id").toString
      if (isRelevantId(id)) {
        val ttps = rec.get("threaded_track").asInstanceOf[GenericData.Array[GenericRecord]]
        nTTPSRead += ttps.size

        val ttpsOut = timeFilteredTTPs(ttps)
        if (!ttpsOut.isEmpty) {
          // TODO - add geographic filtering here
          nRecords += 1
          nTrackPoints += ttpsOut.size

          println(s"record $nRecRead: $id")
          for ((tp,i) <- ttpsOut.asScala.zipWithIndex) println(f"$i%5d: $tp")
        }
      }
    }
    dfr.close
  }

  def listFlatArchive (dfr: DataFileReader[GenericRecord]) = {
    processFilteredFlatArchive (dfr){ rec=>
      println(f"$nRecords%9d: $rec")
    }
  }
}