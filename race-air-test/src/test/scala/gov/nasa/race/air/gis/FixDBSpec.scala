package gov.nasa.race.air.gis

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.gis.GisItemDB
import gov.nasa.race.uom.Length._
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

/**
 * regression test for FixDB (GisItemDB[Fix]
 *
 * to re-create *.rgis run
 *
 *   script/geodb -x "searchval=CALIFORNIA" --out tmp/fix-ca.rgis gov.nasa.race.air.gis.FixDB\$
 */
class FixDBSpec extends AnyFlatSpec with RaceSpec {

  val file = baseResourceFile("fix-ca.rgis")
  lazy val db: FixDB = new FixDB(file)  // load only once

  behavior of "FixDB (GisItemDB[Fix])"

  "FixDB" should "load from memory mapped file" in {

    println(s"loaded FixDB from: $file")
    println(s"schema: ${db.schema}")
    println(s"number of items: ${db.nItems}")

    db.schema shouldBe( classOf[Fix].getName)
    db.nItems shouldBe( 4713)
  }

  "FixDB" should "produce known value for item key lookup" in {
    val fix = db.getItem("KLAUS").get
    println(s" KLAUS -> $fix")
    fix.name shouldBe("KLAUS")
  }

  "FixDB" should "find known nearest fix for given position" in {
    val pos = GeoPosition.fromDegrees(37.59443,-122.38892)
    val (dist,fix) = db.getNearestItem(pos).get
    println(s"${pos.toGenericString2D} -> $fix, dist = $dist")
    fix.name shouldBe("SQIRL")
    assert (dist < Meters(1))
  }

  "FixDB" should "iterate through known, sorted range item ids" in {
    val expected = Array("BRIXX","MAGHA","ZIMYU","CFFKC","SQIRL","TUYUS","VPSCS","URRSA","ORYAN","CFFVX","VPOYS")
    val pos = GeoPosition.fromDegrees(37.62000,-122.38000) // close to KSFO
    val range = Meters(5000)
    var i = 0
    var lastDist = Meters(0)
    println(s"items in range ${pos.toGenericString2D} + $range:")
    db.foreachRangeItemId(pos,range){ (dist,id) =>
      println(f" $i%2d : ${dist.toMeters}%8.0fm, $id")
      id shouldBe( expected(i))
      if (i > 0) assert( dist >= lastDist)
      assert( dist <= range)
      i += 1
      lastDist = dist
    }
  }

  "FixDB" should "iterate through known, sorted items next to given position" in {
    val expected = Array("BRIXX","MAGHA","ZIMYU","CFFKC","SQIRL","TUYUS","VPSCS","URRSA","ORYAN","CFFVX","VPOYS")
    val n = expected.length
    val pos = GeoPosition.fromDegrees(37.62000,-122.38000) // close to KSFO
    println(s"$n closest items to ${pos.toGenericString2D}:")
    val result = db.getNearItems(pos,n)
    for ( ((dist,fix),i) <- result.zipWithIndex) {
      println(f" $i%2d : ${dist.toMeters}%8.0fm, $fix")
      fix.name shouldBe( expected(i))
    }
    result.size shouldBe(n)
  }

  "FixDB" should "support cached query objects" in {
    var nearestFixId: String = "?"
    val nearestQuery = db.createNearestItemQuery(GeoPosition.fromDegrees(0,0))
    val newPos = GeoPosition.fromDegrees(37.62000,-122.38000)
    nearestQuery.setPos(newPos)
    db.processQuery(nearestQuery)
    nearestQuery.withItemId( (dist,id) => nearestFixId = id )
    println(s"closest fix to $newPos is '$nearestFixId'")
    assert( nearestFixId == "BRIXX")
  }

  "FixDB" should "find all fixes starting with letter X" in {
    println("fixes starting with 'X':")
    db.foreach{ fix=>
      if (fix.name.startsWith("X")) println(fix)
    }
  }
}
