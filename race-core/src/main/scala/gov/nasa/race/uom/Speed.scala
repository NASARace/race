package gov.nasa.race.uom

import scala.concurrent.duration.FiniteDuration


/**
  * speed quantities (magnitude of velocity vectors)
  * basis is m/s
  */
object Speed {
  //--- constants
  final val MetersPerSecInKnot = 1852.0 / 3600
  final val MetersPerSecInKmh = 1000.0 / 3600
  final val MetersPerSecInMph = 1609.344 / 3600

  final val Speed0 = new Speed(0)
  final val UndefinedSpeed = new Speed(Double.NaN)
  @inline def isDefined(x: Speed): Boolean = !x.d.isNaN
  final implicit val εSpeed = MetersPerSecond(1e-10)

  //--- constructors
  @inline def MetersPerSecond(d: Double) = new Speed(d)
  @inline def Knots(d: Double) = new Speed(d * MetersPerSecInKnot)
  @inline def KilometersPerHour(d: Double) = new Speed(d * MetersPerSecInKmh)
  @inline def UsMilesPerHour(d: Double) = new Speed(d * MetersPerSecInMph)

  implicit class SpeedConstructor (val d: Double) extends AnyVal {
    def `m/s` = MetersPerSecond(d)
    def kmh = KilometersPerHour(d)
    def `km/h` = KilometersPerHour(d)
    def mph = UsMilesPerHour(d)
    def knots = Knots(d)
    def kn = Knots(d)
  }
}

class Speed protected[uom] (val d: Double) extends AnyVal {
  import Speed._

  @inline def toMetersPerSecond: Double = d
  @inline def toKnots: Double = d / MetersPerSecInKnot
  @inline def toKilometersPerHour: Double = d / MetersPerSecInKmh
  @inline def toKmh = toKilometersPerHour
  @inline def toUsMilesPerHour: Double = d / MetersPerSecInMph
  @inline def toMph = toUsMilesPerHour

  @inline def + (x: Speed): Speed = new Speed(d + x.d)
  @inline def - (x: Speed): Speed = new Speed(d - x.d)

  @inline def * (x: Double): Speed = new Speed(d * x)
  @inline def * (t: FiniteDuration) = new Length((d * t.toMicros)/1e6)
  @inline def / (x: Double): Speed = new Speed(d / x)

  @inline def ≈ (x: Speed)(implicit εSpeed: Speed) = Math.abs(d - x.d) <= εSpeed.d
  @inline def ~= (x: Speed)(implicit εSpeed: Speed) = Math.abs(d - x.d) <= εSpeed.d
  @inline def within (x: Speed, distance: Speed) = Math.abs(d - x.d) <= distance.d

  @inline def < (x: Speed) = d < x.d
  @inline def > (x: Speed) = d > x.d
  @inline def =:= (x: Speed) = d == x.d
  @inline def ≡ (x: Speed) = d == x.d
  // we intentionally omit ==, <=, >=

  @inline def isUndefined = d.isNaN
  @inline def isDefined = !d.isNaN

  override def toString = show
  def show = s"${d}m/s"
  def showKmh = s"${toKmh}kmh"
  def showMph = s"${toMph}mph"
  def showKnots = s"${toKnots}kn"

}