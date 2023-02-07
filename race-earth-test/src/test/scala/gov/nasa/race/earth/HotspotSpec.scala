package gov.nasa.race.earth

import gov.nasa.race
import gov.nasa.race.{SuccessValue, archive}
import gov.nasa.race.common.{JsonSerializable, JsonWriter}
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils.fileContentsAsBytes
import org.apache.commons.compress.archivers.ArchiveEntry
import org.scalatest.flatspec.AnyFlatSpec

import java.io.ByteArrayInputStream

class HotspotSpec extends AnyFlatSpec with RaceSpec {

  "a HotsporParser" should "parse known data" in {
    val input = "38.61877,-121.29271,308.4,0.43,0.46,2020-08-16,1024,1,VIIRS,n,2.0NRT,293.7,1.3,N"
    val writer = new JsonWriter()
    writer.readableDateTime(true)

    val parser = new ViirsHotspotParser(){}
    if (parser.initialize(input.getBytes)) {
      parser.parseHotspot() match {
        case SuccessValue(hotspot) =>
          writer.clear()
          println(writer.toJson(hotspot))

          assert( hotspot.date == DateTime.parseISO("2020-08-16T10:24:00Z"))
          hotspot.position.latDeg shouldBe( 38.61877 +- 0.000001)
          hotspot.position.lonDeg shouldBe( -121.29271 +- 0.000001)
          hotspot.brightness.toKelvin shouldBe( 308.4 +- 0.01)

        case race.Failure(msg) => fail(msg)
      }
    } else fail("parser failed to initialize")
  }

  "a HotspotArchiveReader" should "read correct batches of records from known archive" in {
    val fileName = "viirs-1-081620-092220.csv.gz"
    val is = baseResourceStream(fileName)
    var nEntries = 0
    var nHotspots = 0
    var d: DateTime = DateTime.UndefinedDateTime
    val r = new ViirsHotspotArchiveReader(is, fileName, 8192)
    var next: Option[archive.ArchiveEntry] = r.readNextEntry()
    val writer = new JsonWriter()
    writer.readableDateTime(true)

    while (next.isDefined) {
      val e = next.get
      val msg = e.msg
      nEntries += 1

      if (d.isDefined) assert( e.date > d)
      d = e.date

      msg match {
        case list:Seq[_] =>
          nHotspots += list.size
          println(s"----- $nEntries: ${e.date} : ${list.size} records")

          list.foreach { o=>
            o match {
              case hs: Hotspot =>
                assert (hs.date <= e.date)
                // and possibly some more checks here..
              case _ => fail(s"${o.getClass} not a Hotspot")
            }
          }
      }

      next = r.readNextEntry()
    }

    println(s"read $nEntries archive entries with $nHotspots records")
    assert( nHotspots == 21024)
  }
}
