/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.air.actor

import java.io.{File, FileInputStream, InputStream}
import java.util.zip.GZIPInputStream

import com.typesafe.config.Config
import gov.nasa.race.air.{TaisTrack, TaisTrackCSVReader, TRACON}
import gov.nasa.race.common.CSVInputStream
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.ContinuousTimeRaceActor
import gov.nasa.race.uom.DateTime

import scala.util.matching.Regex

/**
  * actor to replay TATrack archives in CSV format
  */
class TATrackReplayActor (val config: Config) extends SubjectImporter[TRACON] with ContinuousTimeRaceActor {

  final val batchStartPattern = ",---,".getBytes

  val file: File = config.getNonEmptyFile("pathname")
  val in: CSVInputStream = createCSVInputStream
  val recordReader = new TaisTrackCSVReader(in)

  var nextSubject: String = null
  var nextDate: DateTime = DateTime.UndefinedDateTime // this is simTime
  var nRecords: Int = 0 // in next batch
  var nextTracks: Array[TaisTrack] = Array.empty        // set once we encounter a batch we should publish

  def createCSVInputStream: CSVInputStream = {
    val fis = new FileInputStream(file)
    val is: InputStream = if (file.getName.endsWith(".gz")) new GZIPInputStream(fis, 65336) else fis
    new CSVInputStream(is)
  }

  //
  def positionOnNextSubjectBatch: Boolean = {
    while (!in.matchBytes(batchStartPattern)) in.skipRecord
    if (in.hasNext) {
      nextSubject = in.readString
      nextDate = DateTime.ofEpochMillis(in.readLong)
      nRecords = in.readInt
      in.wasEndOfRecord && (nRecords > 0)

    } else {
      false
    }
  }

  def readNextBatch: Array[TaisTrack] = {
    val a = new Array[TaisTrack](nRecords)
    var i = 0
    while (i < a.length) {
      a(i) = recordReader.read(nextSubject)
      i += 1
    }
    a
  }

  //--- SubjectImporter
  override def topicSubject (topic: Any): Option[TRACON] = {
    topic match {
      case Some(tracon:TRACON) => TRACON.tracons.get(tracon.id)
      case Some(traconId: String) => TRACON.tracons.get(traconId)
      case _ => None
    }
  }
  override def subjectRegex(tracon:TRACON): Option[Regex] = None // TODO - this should go away since it implies message data input
}
