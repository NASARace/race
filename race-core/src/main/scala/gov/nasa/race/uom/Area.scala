/*
 * Copyright (c) 2016, United States Government, as represented by the
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

import Length._

/**
  * area quantities
  * underlying unit is square meter
  */
object Area {
  final implicit val εArea = SquareMeters(1e-10)

  //--- constructors
  def SquareMeters (d: Double) = new Area(d)

  @inline def √ (a: Area)(implicit r: AreaDisambiguator.type): Length = Meters(Math.sqrt(a.d))

  implicit class AreaConstructor (val d: Double) extends AnyVal {
    @inline def squareMeters = SquareMeters(d)
    @inline def `m²` = SquareMeters(d)
  }
}

/**
  * derived unit for squared lengths
  * basis is m²
  */
class Area protected[uom] (val d: Double) extends AnyVal {

  @inline def toSquareMeters: Double = d

  @inline def / (x: Double): Area = new Area(d/x)
  @inline def / (x: Length)(implicit r: AreaDisambiguator.type): Length = new Length(d/x.d)
  @inline def * (x: Double): Area = new Area(d * x)

  @inline def + (x: Area): Area = new Area(d + x.d)
  @inline def - (x: Area): Area = new Area(d - x.d)

  @inline def ≈ (x: Area)(implicit εArea: Area) = Math.abs(d - x.d) <= εArea.d
  @inline def ~= (x: Area)(implicit εArea: Area) = Math.abs(d - x.d) <= εArea.d
  @inline def within (x: Area, tolerance: Area) = Math.abs(d - x.d) <= tolerance.d

  @inline def < (x: Area) = d < x.d
  @inline def > (x: Area) = d > x.d
  @inline def =:= (x: Area) = d == x.d
  @inline def ≡ (x: Area) = d == x.d
  // we intentionally omit ==, <=, >=

  override def toString = show
  def show = s"${d}m²"
}
