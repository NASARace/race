package gov.nasa.race.common

import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec
import java.lang.Math._

class FHTInterpolant3Spec extends AnyFlatSpec with RaceSpec {

  "a FHT3Interpolant" should "work support generic data model" in {
    val r = (new SampleT3).interpolateFH
    val tStart = ((r.tLeft + 500) / 1000) * 1000
    val tEnd = tStart + 20000

    for (p <- r.iterator(tStart, tEnd, 1000)) {
      val t = p.getTime
      val x = p._0
      val y = p._1
      val z = p._2
      println(s"$t : $x,\t$y,\t$z")
      // TODO add oracle
    }
  }

  @inline def rad(deg: Long): Double = deg.toDouble * Math.PI / 180.0
  @inline def rad(deg: Double): Double = deg * Math.PI / 180.0

  def fx (t: Long): Double = sin(rad(t))
  def fy (t: Long): Double = cos(rad(t))
  def fz (t: Long): Double = 5.0 - (t / 100.0)

  "a FHT3Interpolant" should "sufficiently close approximate analytic functions" in {
    val ts = Array[Long]( 0, 12, 31, 40, 45, 48, 55, 62, 69, 74, 80, 83, 90 )
    val r = new SyntheticT3(ts,fx,fy,fz).interpolateFH
    val tStart = 0
    val tEnd = 90

    for (p <- r.iterator(tStart, tEnd, 10)) {
      val t = p.getTime
      val x = p._0
      val y = p._1
      val z = p._2

      val ex = abs(fx(t) - x)
      val ey = abs(fy(t) - y)
      val ez = abs(fz(t) - z)

      println(f"$t%3d : [$x%.4f, $y%.4f, $z%.4f],   e= [$ex%.3e, $ey%.3e, $ez%.3e] ")
    }
  }
}
