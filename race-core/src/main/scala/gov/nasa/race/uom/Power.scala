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
import gov.nasa.race.uom.Power.wattToHp

object Power {
  final implicit val εPower = Watts(1e-9)
  final val UndefinedPower = new Power(Double.NaN)

  @inline def Watts(d: Double): Power = Watt(d)
  @inline def Watt(d: Double): Power = new Power(d)
  @inline def KiloWatt(d: Double): Power = Watt(d*1e3)
  @inline def MegaWatt(d: Double): Power = Watt(d*1e6)
  @inline def Hp(d: Double): Power = Watt( hpToWatt(d) )
  //... and more to follow

  def hpToWatt(hp: Double): Double = hp * 745.7
  def wattToHp(watts: Double): Double = watts / 745.7
}

/**
  * base unit is W
  */
class Power protected[uom] (val d: Double) extends AnyVal with Ordered[Power] with MaybeUndefined {

  @inline def toWatt: Double = d
  @inline def toHp: Double = wattToHp(d)
  @inline def toKiloWatt: Double = d / 1e3
  @inline def toMegaWatt: Double = d / 1e6

  @inline def toRoundedWatt: Long = toWatt.round
  @inline def toRoundedHp: Long = toHp.round

  @inline override def compare (other: Power): Int = if (d > other.d) 1 else if (d < other.d) -1 else 0
  @inline override def compareTo (other: Power): Int = compare(other)

  @inline override def isDefined = !d.isNaN
  @inline override def isUndefined: Boolean = d.isNaN

  @inline def + (x: Power) = new Power(d + x.d)
  @inline def - (x: Power) = new Power(d - x.d)

  @inline def * (c: Double) = new Power(d * c)

  @inline def / (c: Double): Power = new Power(d / c)
  @inline def / (x: Power)(implicit r: PowerDisambiguator.type): Double = d / x.d

  @inline def ≈ (x: Power)(implicit εPower: Power): Boolean = Math.abs(d - x.d) <= εPower.d
  @inline def ~= (x: Power)(implicit εPower: Power): Boolean = Math.abs(d - x.d) <= εPower.d

  @inline def =:= (x: Power): Boolean = d == x.d  // use this if you really mean equality
  @inline def ≡ (x: Power): Boolean = d == x.d

  override def toString: String = show

  def show = {
    if (d > 1e6) f"$toMegaWatt%.2fMW"
    else if (d > 1000) f"$toKiloWatt%.2fkW"
    else f"$toWatt%.2fW"
  }

}
