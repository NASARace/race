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
package gov.nasa.race.archive

import java.io.InputStream

import gov.nasa.race.track.avro.TrackPoint
import org.apache.avro.file.DataFileStream
import org.apache.avro.specific.SpecificDatumReader
import org.joda.time.DateTime

/**
  * an ArchiveReader for Avro archive streams that contain TrackPoint records
  *
  * Note that this reader is not thread safe and does NOT allocate a new TrackPoint per record since it
  * is supposed to be used from a context that transforms the TrackPoints into one of our internal types (e.g.
  * FlightPos objects). TrackPoints are just the external (cross language) format
  *
  * Alternatively we could derive a class from the Avro generated TrackPoint that implements the TrackObject interface,
  * but the TrackPoint API is too wide and permissive for most of our purposes (all fields are mutable and there is
  * no support for units-of-measure or any other of our basic types such as LatLonPos or DateTime)
  */
class TrackPointArchiveReader (val istream: InputStream) extends StreamArchiveReader {
  val dfs = new DataFileStream(istream,new SpecificDatumReader[TrackPoint])
  val recCache = new TrackPoint

  override def hasMoreData = dfs.hasNext

  override def close = dfs.close

  override def readNextEntry = {
    val tp = dfs.next(recCache)
    someEntry(new DateTime(tp.getDate),tp)
  }
}
