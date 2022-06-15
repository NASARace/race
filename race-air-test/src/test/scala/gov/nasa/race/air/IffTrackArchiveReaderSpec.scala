package gov.nasa.race.air

import gov.nasa.race.air.actor.IffTrackArchiveReader
import gov.nasa.race.archive
import gov.nasa.race.test.RaceSpec
import gov.nasa.race.uom.DateTime
import org.scalatest.flatspec.AnyFlatSpec

/**
  * reg test for parsing IffTrack records
  */
class IffTrackArchiveReaderSpec extends AnyFlatSpec with RaceSpec {

  "an IffTrackArchiveReader" should "parse a known archive" in {
    val fname = "iff.csv"
    val is = baseResourceStream(fname)
    var nEntries = 0
    val reader = new IffTrackArchiveReader(is, fname, 8192)
    var next: Option[archive.ArchiveEntry] = reader.readNextEntry()
    var lastDate = DateTime.UndefinedDateTime

    while (next.isDefined) {
      val e = next.get
      val msg = e.msg
      nEntries += 1

      msg match {
        case t: IffTrack =>
          println(t.toShortString)

          if (lastDate.isDefined) {
            assert( t.date >= lastDate)
            lastDate = t.date
          }
        case o =>
          fail(s"unexpected result: $o")
      }

      next = reader.readNextEntry()
    }

    assert(nEntries == 150)
  }
}
