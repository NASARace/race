package gov.nasa.race.uom

import Math._
import gov.nasa.race.common._
import scala.language.postfixOps

/**
  * angle quantities
  * underlying unit is radians
  */
object Angle {

  //--- constants
  final val π = Math.PI
  final val TwoPi = π * 2.0
  final val DegreesInRadian = π / 180.0
  final val Angle0 = new Angle(0)
  final val UndefinedAngle = new Angle(Double.NaN)
  @inline def isDefined(x: Angle): Boolean  = x.d != Double.NaN

  final implicit val εAngle = Degrees(1.0e-10)  // provide your own if application specific


  //--- utilities
  @inline def normalizeRadians (d: Double) = d - π*2 * Math.floor((d + π) / (π*2)) // -π..π
  @inline def normalizeRadians2Pi (d: Double) = if (d<0) d % TwoPi + TwoPi else d % TwoPi  // 0..2π
  @inline def normalizeDegrees (d: Double) =  if (d < 0) d % 360 + 360 else d % 360 // 0..360
  @inline def normalizedDegreesToRadians (d: Double) = normalizeDegrees(d) * DegreesInRadian

  @inline def AbsDiff(a1: Angle, a2: Angle) = {
    val d1 = normalizeRadians2Pi(a1.d)
    val d2 = normalizeRadians2Pi(a2.d)
    val dd = abs(d1 - d2)
    if (dd > π) Radians(TwoPi - dd) else Radians(dd)
  }

  //--- trigonometrics functions
  // note we have to use upper case here because we otherwise get ambiguity errors when
  // importing both Angle._ and Math._ versions. This is a consequence of using a AnyVal Angle

  @inline def Sin(a:Angle) = sin(a.d)
  @inline def Sin2(a:Angle) = Sin(a)`²`
  @inline def Cos(a:Angle) = cos(a.d)
  @inline def Cos2(a:Angle) = Cos(a)`²`
  @inline def Tan(a:Angle) = tan(a.d)
  @inline def Tan2(a:Angle) = Tan(a)`²`
  @inline def Asin(a:Angle) = asin(a.d)
  @inline def Asin2(a:Angle) = Asin(a)`²`
  @inline def Acos(a:Angle) = acos(a.d)
  @inline def Acos2(a:Angle) = Acos(a)`²`
  @inline def Atan(a:Angle) = atan(a.d)
  @inline def Atan2(a:Angle) = Atan(a)`²`


  //--- Angle constructors
  @inline def Degrees (d: Double) = new Angle(d * DegreesInRadian)
  @inline def Radians (d: Double) = new Angle(d)

  implicit class AngleConstructor (val d: Double) extends AnyVal {
    @inline def degrees = Degrees(d)
    @inline def radians = Radians(d)
  }

  //--- to support expressions with a leading unit-less numeric factor
  implicit class AngleDoubleFactor (val d: Double) extends AnyVal {
    @inline def * (x: Angle) = new Angle(x.d * d)
  }
  implicit class AngleIntFactor (val d: Int) extends AnyVal {
    @inline def * (x: Angle) = new Angle(x.d * d)
  }
}

class Angle protected[uom] (val d: Double) extends AnyVal {
  import Angle._

  //---  Double converters
  @inline def toRadians: Double = d
  @inline def toDegrees: Double = d / DegreesInRadian
  @inline def toNormalizedDegrees: Double = normalizeDegrees(toDegrees)

  //--- numeric and comparison operators
  @inline def + (x: Angle) = new Angle(d + x.d)
  @inline def - (x: Angle) = new Angle(d - x.d)

  @inline def * (x: Double) = new Angle(d * x)
  @inline def / (x: Double) = new Angle(d / x)
  @inline def / (x: Angle)(implicit r: AngleDisambiguator.type): Double = d / x.d

  @inline def ≈ (x: Angle)(implicit εAngle: Angle) = Math.abs(d - x.d) <= εAngle.d
  @inline def ~= (x: Angle)(implicit εAngle: Angle) = Math.abs(d - x.d) <= εAngle.d
  @inline def within (x: Angle, tolerance: Angle) = Math.abs(d - x.d) <= tolerance.d

  @inline def < (x: Angle) = d < x.d
  @inline def > (x: Angle) = d > x.d
  @inline def =:= (x: Angle) = d == x.d
  @inline def ≡ (x: Angle) = d == x.d
  // we intentionally omit ==, <=, >=

  @inline def isUndefined = d == Double.NaN
  @inline def isDefined = d != Double.NaN

  //--- string converters
  override def toString = show // NOTE - calling this will cause allocation, use 'show'
  def show: String = s"${toNormalizedDegrees}°"
  def showRounded: String = f"${toNormalizedDegrees}%.0f°"
  def showRounded5: String = f"${toNormalizedDegrees}%.5f°"
}