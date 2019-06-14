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

import java.io.File

import gov.nasa.race.util.ClassLoaderUtils
import org.apache.avro.file.DataFileReader
import org.apache.avro.specific.{SpecificDatumReader, SpecificRecord}

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

/**
  * support for deserializing a Apache Avro archive
  */
class AvroDeserializer[T <: SpecificRecord : ClassTag] (val cls: Class[T], val file: File) {

  val datumReader = new SpecificDatumReader[T](cls)
  val dataFileReader = new DataFileReader[T](file,datumReader)

  def foreachWithCache(t: T)(f: T=>Unit): Unit = {
    while (dataFileReader.hasNext) {
      f(dataFileReader.next(t))
    }
  }

  protected def contents: ListBuffer[T] = {
    val l = new ListBuffer[T]
    while (dataFileReader.hasNext) {
      l += dataFileReader.next
    }
    l
  }

  def toSeq: Seq[T] = contents.toList

  def toArray: Array[T] = contents.toArray

  def map[A](f: T=>A): Seq[A] = {
    val t: T = ClassLoaderUtils.newInstanceOf(cls) // as of Scala 2.12.7 needs explicit instance type
    val l = new ListBuffer[A]
    while (dataFileReader.hasNext) {
      l += f(dataFileReader.next(t))
    }
    l.toSeq
  }
}
