/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.uom

import gov.nasa.race.MaybeUndefined

object Temperature {
  final implicit val εTemperature = Kelvin(1e-9)

  final val UndefinedTemperature = new Temperature(Double.NaN)
  final val Temperature0 = Kelvin(0.0)

  @inline def Kelvin(d: Double) = new Temperature(d)
  @inline def Celsius(d: Double) = new Temperature(d + 273.15)
  @inline def Fahrenheit(d: Double) = new Temperature( ((d - 32.0) * 5.0/9.0) + 273.15)

  @inline def kelvinToCelsius(d: Double): Double = d - 273.15
  @inline def celsiusToKelvin(d: Double): Double = d + 273.15;

  @inline def kelvinToFahrenheit(d: Double): Double = ((d - 273.15) * 9.0/5.0) + 32.0
  @inline def fahrenheitToKelvin(d: Double): Double = ((d - 32.0) * 5.0/9.0) + 273.15
}
import Temperature._

/**
  * base unit is K
  */
class Temperature protected[uom] (val d: Double) extends AnyVal with Ordered[Temperature] with MaybeUndefined {

  @inline def toKelvin: Double = d
  @inline def toCelsius: Double = kelvinToCelsius(d)
  @inline def toFahrenheit: Double = kelvinToFahrenheit(d)

  @inline def toRoundedKelvin: Long = d.round
  @inline def toRoundedCelsius: Long = toCelsius.round
  @inline def toRoundedFahrenheit: Long = toFahrenheit.round

  @inline override def isDefined = !d.isNaN
  @inline override def isUndefined: Boolean = d.isNaN

  // comparison
  @inline override def < (x: Temperature) = d < x.d
  @inline override def <= (x: Temperature) = d <= x.d
  @inline override def > (x: Temperature) = d > x.d
  @inline override def >= (x: Temperature) = d >= x.d

  @inline override def compare (other: Temperature): Int = if (d > other.d) 1 else if (d < other.d) -1 else 0
  @inline override def compareTo (other: Temperature): Int = compare(other)

  @inline def + (x: Temperature) = new Temperature(d + x.d)
  @inline def - (x: Temperature) = new Temperature(d - x.d)

  @inline def * (c: Double) = new Temperature(d * c)

  @inline def / (c: Double): Temperature = new Temperature(d / c)
  @inline def / (x: Temperature)(implicit r: TemperatureDisambiguator.type): Double = d / x.d

  @inline def ≈ (x: Temperature)(implicit εTemperature: Temperature): Boolean = Math.abs(d - x.d) <= εTemperature.d
  @inline def ~= (x: Temperature)(implicit εTemperature: Temperature): Boolean = Math.abs(d - x.d) <= εTemperature.d

  @inline def =:= (x: Temperature): Boolean = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Temperature): Boolean = d == x.d

  override def toString = show   // calling this would cause allocation
  def show = s"${d}K"
  @inline def showKelvin = show
  def showFahrenheit = s"${toFahrenheit}°F"
  def showCelsius = s"${toCelsius}°C"

  def showRounded = f"${d}%.0fm"
  def showRoundedFahrenheit = f"${toFahrenheit}%.0f°F"
  def showRoundedCelsius = f"${toCelsius}%.0f°F"
}
