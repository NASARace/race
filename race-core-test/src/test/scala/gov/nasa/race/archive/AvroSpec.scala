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

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

case class TestRecordMappable (s: String)

/**
  * unit test for Avro based serializing/de-serializing
  */
class AvroSpec extends AnyFlatSpec with RaceSpec {
  "AvroSerializer and AvroDeserializer" should "reproduce roundtrip input values" in {
    withEmptyTestOutputFile("avrotestrecords-1.avro"){ archive =>
      val recs = Seq(
        new AvroTestRecord("one"),
        new AvroTestRecord("two")
      )

      val avroSerializer = new AvroSerializer[AvroTestRecord](classOf[AvroTestRecord],archive)
      println(s"writing avro archive ${archive.getAbsolutePath}")
      avroSerializer.serialize(recs)

      assert(archive.isFile)
      assert(archive.length > 0)

      val avroDeserializer = new AvroDeserializer[AvroTestRecord](classOf[AvroTestRecord],archive)
      avroDeserializer.foreachWithCache(new AvroTestRecord)(println)

      val avroDeserializer1 = new AvroDeserializer[AvroTestRecord](classOf[AvroTestRecord],archive)
      val rs = avroDeserializer1.toSeq
      rs.foreach(println)

      assert(rs == recs)
    }
  }

  "AvroSerializer and Deserializer" should "support mapping from/to (cache) objects" in {
    withEmptyTestOutputFile("avrotestrecords-2.avro") { archive =>
      val recs = Seq(
        TestRecordMappable("eins"),
        TestRecordMappable("zwei")
      )

      val avroSerializer = new AvroSerializer[AvroTestRecord](classOf[AvroTestRecord], archive)
      println(s"writing avro archive ${archive.getAbsolutePath}")
      avroSerializer.serializeMappedCache(recs)((trMappable, tr) => tr.setStr(trMappable.s))

      assert(archive.isFile)
      assert(archive.length > 0)

      val avroDeserializer = new AvroDeserializer[AvroTestRecord](classOf[AvroTestRecord], archive)
      val rs = avroDeserializer.map(tr => TestRecordMappable(tr.getStr.toString))
      rs.foreach(println)

      assert(rs == recs)
    }
  }
}
