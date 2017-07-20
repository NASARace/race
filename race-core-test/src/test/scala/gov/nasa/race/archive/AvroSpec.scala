package gov.nasa.race.archive

import gov.nasa.race.test.RaceSpec
import org.scalatest.FlatSpec

/**
  * unit test for Avro based serializing/de-serializing
  */
class AvroSpec extends FlatSpec with RaceSpec {
  "AvroSerializer and AvroDeserializer" should "reproduce roundtrip input values" in {
    val archive = testOutputFile("avrotestrecords.avro")
    val avroSerializer = new AvroSerializer[AvroTestRecord](classOf[AvroTestRecord],archive)

    val recs = Seq(
      new AvroTestRecord("one"),
      new AvroTestRecord("two")
    )

    println(s"writing avro archive ${archive.getAbsolutePath}")
    avroSerializer.serialize(recs)

    assert(archive.isFile)
    assert(archive.length > 0)

    val avroDeserializer = new AvroDeserializer[AvroTestRecord](classOf[AvroTestRecord],archive)
    avroDeserializer.foreachWithCache(new AvroTestRecord)(println)

    val avroDeserializer1 = new AvroDeserializer[AvroTestRecord](classOf[AvroTestRecord],archive)
    val rs = avroDeserializer1.toSeq
    assert(rs == recs)
  }
}
