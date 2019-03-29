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
package gov.nasa.race.geo

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.{Angle, Length}

/**
  * a 3D distance filter with variable reference point and distance
  * We assume pass() is called more often than setting a new reference and distance
  */
class VarEuclideanDistanceFilter3D (φ: Angle, λ: Angle, alt: Length, d: Length, val config: Config=null) extends ConfigurableFilter {

  def this (conf: Config) = this(
    Degrees(conf.getDouble("lat")),
    Degrees(conf.getDouble("lon")),
    Feet(conf.getDouble("alt")),
    NauticalMiles(conf.getDouble("radius-nm")),
    conf
  )

  var refLat = φ
  var refLon = λ
  var refAlt = alt
  var dist = d

  var maxAngularDiff = angularDistance(dist)

  def updateReference (φ: Angle, λ: Angle, alt: Length): Unit = {
    refLat = φ
    refLon = λ
    refAlt = alt
  }

  def updateDistance (d: Length): Unit = {
    dist = d
    maxAngularDiff = angularDistance(dist)
  }


  override def pass (o: Any): Boolean = {
    o match {
      case p: GeoPositioned => pass(p)
      case other => false
    }
  }

  def pass (φ: Angle, λ: Angle, alt: Length): Boolean = {
    (!alt.isDefined || (Abs(refAlt - alt) < dist)) &&
    (absDiff(refLat,φ) < maxAngularDiff) &&
    (absDiff(refLon,λ) < maxAngularDiff) &&
    (Euclidean.distance(refLat,refLon,refAlt, φ,λ,alt) < dist)
  }

  @inline def pass (ap: GeoPositioned): Boolean = {
    val pos = ap.position
    pass(pos.φ, pos.λ, pos.altitude)
  }
}
