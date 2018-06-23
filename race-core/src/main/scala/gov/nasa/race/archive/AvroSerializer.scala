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

import gov.nasa.race.util.ClassLoaderUtils._

import org.apache.avro.Schema
import org.apache.avro.file.DataFileWriter
import org.apache.avro.specific.{SpecificDatumWriter, SpecificRecord}

/**
  * support for serializing with Apache Avro
  * note that Avro always stores the schema with the data, which is suitable for archives
  * but not for serializing single messages. This is why we keep Avro support in the
  * archive package
  */
class AvroSerializer[T <: SpecificRecord] (val cls: Class[T], val file: File) {

  val datumWriter = new SpecificDatumWriter[T](cls)
  val dataFileWriter = new DataFileWriter[T](datumWriter)

  private def serialize (f: => Any): Unit = {
    try {
      create
      f
    } finally {
      close
    }
  }

  def serialize (ts: Seq[T]): Unit = serialize(append(ts))
  def serializeMapped[A](as: Seq[A])(f: A=>T): Unit = serialize( appendMapped(as)(f))
  def serializeMappedCache[A](as: Seq[A])(f: (A,T)=>Unit): Unit = serialize( appendMappedCache(as,newInstanceOf(cls))(f))


  def create: Unit = {
    val schema = cls.getMethod("getClassSchema").invoke(null).asInstanceOf[Schema]
    dataFileWriter.create(schema,file)
  }

  def append(t: T): AvroSerializer[T] = {
    dataFileWriter.append(t)
    this
  }

  def append(ts: Seq[T]): AvroSerializer[T] = {
    ts.foreach(dataFileWriter.append)
    this
  }

  def append(ts: Array[T]): AvroSerializer[T] = {
    ts.foreach(dataFileWriter.append)
    this
  }

  def appendMapped[A](as: Seq[A])(f: A=>T): AvroSerializer[T] = {
    as.foreach( a=> dataFileWriter.append(f(a)))
    this
  }

  def appendMappedCache[A](as: Seq[A], t: T = newInstanceOf(cls))(f: (A,T)=>Unit): AvroSerializer[T] = {
    as.foreach( a=> {
      f(a,t)
      dataFileWriter.append(t)
    })
    this
  }

  def close: Unit = dataFileWriter.close
}
