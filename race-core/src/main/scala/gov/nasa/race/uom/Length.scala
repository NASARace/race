package gov.nasa.race.uom

import scala.concurrent.duration.Duration

/**
  * length quantities
  * underlying unit is meters
  */
object Length {
  //--- constants
  final val MetersInNauticalMile = 1852.0
  final val MetersInUsMile = 1609.344
  final val MetersInFoot = 0.3048
  final val MetersInInch = 0.0254

  final val Length0 = Meters(0)
  final val UndefinedLength = Meters(Double.NaN)
  @inline def isDefined(x: Length): Boolean = x.d != Double.NaN

  final implicit val εLength = Meters(1e-9)

  //--- Length constructors (basis is meter)
  @inline def Kilometers(d: Double) = new Length(d*1000.0)
  @inline def Meters(d: Double) = new Length(d)
  @inline def Centimeters(d: Double) = new Length(d/100.0)
  @inline def Millimeters(d: Double) = new Length(d/1000.0)
  @inline def NauticalMiles(d: Double) = new Length(d * MetersInNauticalMile)
  @inline def UsMiles(d: Double) = new Length(d * MetersInUsMile)
  @inline def Feet(d: Double) = new Length(d * MetersInFoot)
  @inline def Inches(d: Double) = new Length(d * MetersInInch)

  implicit class LengthConstructor (val d: Double) extends AnyVal {
    @inline def kilometers = Kilometers(d)
    @inline def km = Kilometers(d)
    @inline def meters = Meters(d)
    @inline def m = Meters(d)
    @inline def centimeters = Centimeters(d)
    @inline def cm =  Centimeters(d)
    @inline def millimeters = Millimeters(d)
    @inline def mm = Millimeters(d)
    @inline def nauticalMiles = NauticalMiles(d)
    @inline def nm = NauticalMiles(d)
    @inline def usMiles = UsMiles(d)
    @inline def mi = UsMiles(d)
    @inline def feet = Feet(d)
    @inline def ft = Feet(d)
    @inline def inches = Inches(d)
    @inline def in = Inches(d)
  }

  //--- to support expressions with a leading unit-less numeric factor
  implicit class LengthDoubleFactor (val d: Double) extends AnyVal {
    @inline def * (x: Length) = new Length(x.d * d)
  }
  implicit class LengthIntFactor (val d: Int) extends AnyVal {
    @inline def * (x: Length) = new Length(x.d * d)
  }

  @inline final def meters2Feet(m: Double) = m / MetersInFoot
  @inline final def feet2Meters(f: Double) = f * MetersInFoot
}


/**
  * basis is meters, ISO symbol is 'm'
  */
class Length protected[uom] (val d: Double) extends AnyVal {
  import Length._

  //--- Double converters
  @inline def toKilometers = d / 1000.0
  @inline def toMeters = d
  @inline def toCentimeters = d * 100.0
  @inline def toMillimeters = d * 1000.0
  @inline def toUsMiles = d / MetersInUsMile
  @inline def toFeet = d / MetersInFoot
  @inline def toInches = d / MetersInInch
  @inline def toNauticalMiles = d / MetersInNauticalMile

  @inline def + (x: Length) = new Length(d + x.d)
  @inline def - (x: Length) = new Length(d - x.d)

  @inline def * (c: Double) = new Length(d * c)
  @inline def * (c: Length)(implicit r: LengthDisambiguator.type) = new Area(d * c.d)

  @inline def / (c: Double): Length = new Length(d / c)
  @inline def / (x: Length)(implicit r: LengthDisambiguator.type): Double = d / x.d

  @inline def / (t: Duration) = new Speed(d/t.toSeconds)

  @inline def ≈ (x: Length)(implicit εLength: Length) = Math.abs(d - x.d) <= εLength.d
  @inline def ~= (x: Length)(implicit εLength: Length) = Math.abs(d - x.d) <= εLength.d
  @inline def within (x: Length, distance: Length) = Math.abs(d - x.d) <= distance.d

  @inline def < (x: Length) = d < x.d
  @inline def > (x: Length) = d > x.d
  @inline def =:= (x: Length) = d == x.d
  @inline def ≡ (x: Length) = d == x.d
  // we intentionally omit ==, <=, >=

  @inline def isUndefined = d == Double.NaN
  @inline def isDefined = d != Double.NaN

  override def toString = show   // calling this would cause allocation
  def show = s"${d}m"
  def showMeters = s"${d}m"
  def showFeet = s"${toFeet}ft"
  def showNauticalMiles = s"${toNauticalMiles}nm"
  def showNm = showNauticalMiles
  def showUsMiles = s"${toUsMiles}mi"
  def showKilometers = s"${toKilometers}km"
}