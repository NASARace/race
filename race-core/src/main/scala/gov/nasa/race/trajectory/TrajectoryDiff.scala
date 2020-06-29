/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.trajectory

import gov.nasa.race.common.Nat.N3
import gov.nasa.race.common.{SampleStats, TInterpolant}
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.{Angle, DateTime, Length, AngleStats, LengthStats, Time}

object TrajectoryDiff {

  /**
    * calculate differences between two trajectories
    *
    * @param refTrajectory     the reference trajectory that will be interpolated
    * @param diffTrajectory    the trajectory for which the deviation to refTrajectory will be calculated
    * @param getInterpolant    function to create a interpolator for refTrajectory
    * @param computeDistance2D function to compute 2D (lat/lon) distance between two trajectory points
    * @param computeDiffAngle  function to compute angle between two trajectory points
    * @return
    */
  def calculate(refSource: String,
                refTrajectory: Trajectory,
                diffSource: String,
                diffTrajectory: Trajectory,
                getInterpolant: Trajectory => TInterpolant[N3, TDP3],
                areaFilter: GeoPosition => Boolean,
                computeDistance2D: (GeoPosition, GeoPosition) => Length,
                computeDiffAngle: (GeoPosition, GeoPosition) => Angle
               ): Option[TrajectoryDiff] = {
    if (refTrajectory.size < 2) {
      return None // nothing we can interpolate
    }

    val refIntr = getInterpolant(refTrajectory)

    val dist2DStats = new LengthStats
    val altDiffStats = new LengthStats
    val angleDiffStats = new AngleStats

    val pMax = new TDP3
    val pRefMax = new TDP3
    val pMin = new TDP3
    val pRefMin = new TDP3

    diffTrajectory.foreach(new TDP3) { p =>
      if (areaFilter(p) && refTrajectory.includesDate(p.epochMillis)) {
        val pRef = refIntr.eval(p.millis)
        if (areaFilter(pRef)) {
          val dist = computeDistance2D(pRef, p)
          dist2DStats += dist
          if (dist2DStats.isMaximum(dist)) {
            pMax.update(p)
            pRefMax.update(pRef)
          }
          if (dist2DStats.isMinimum(dist)) {
            pMin.update(p)
            pRefMin.update(pRef)
          }

          altDiffStats += (pRef.altitude - p.altitude)
          angleDiffStats += computeDiffAngle(pRef, p)
        }
      }
    }

    if (dist2DStats.numberOfSamples > 0) {
      Some( new TrajectoryDiff(
        refSource,
        refTrajectory,
        diffSource,
        diffTrajectory,
        dist2DStats,
        pRefMax, pMax, pMax.epochMillis,
        pRefMin, pMin, pMin.epochMillis,
        angleDiffStats,
        altDiffStats
      ))

    } else {
      None
    }
  }

  /**
    * iteratively determine the closest point on a given trajectory, using the
    * provided interpolant, delta t, and distance calculation
    */
  def closestPointIter(trajectory: Trajectory, pos: GeoPosition, dt: Time,
                       getInterpolant: Trajectory => TInterpolant[N3, TDP3],
                       computeDistance: (GeoPosition, GeoPosition) => Length,
                      ): Option[TDP3] = {
    val pMin = new TDP3
    var dMin = Double.MaxValue
    val intr = getInterpolant(trajectory)
    val it = intr.iterator(trajectory.getFirstDate.toEpochMillis, trajectory.getLastDate.toEpochMillis, dt.toMillis)

    while (it.hasNext) {
      val p = it.next()
      val d = Math.abs(computeDistance(pos, p).toMeters)
      if (d < dMin) { // distance decreasing
        dMin = d
        pMin.update(p)
      } else { // distance increasing
        return Some(pMin)
      }
    }

    // no minimum distance found
    None
  }

  /**
    * get closest point on given trajectory with small lat/lon deltas, using linear interpolation
    * note this treats lat/lon as euclidean coords - don't use for high speed or large report interval
    *
    * TODO - handle pole anomaly and add pre-test for foot point outside of trajectory
    */
  def closestPointLinear(trajectory: Trajectory, pos: GeoPosition): Option[TDP3] = {
    import Math._
    def rad(deg: Double): Double = deg * PI / 180

    val posLat = pos.φ.toDegrees
    val posLon = pos.λ.toDegrees

    val it = trajectory.iterator(new TDP3)
    val pLast = it.next().clone

    while (it.hasNext) {
      val p = it.next()

      val avgLat = (p._0 + pLast._0) / 2
      val cfx = cos(rad(avgLat)) // x (lon) correction factor to make lat/lon units equidistant
      val dLon = p._1 - pLast._1
      val dLat = p._0 - pLast._0

      val vy = dLat
      val vx = dLon / cfx

      val wy = posLat - pLast._0
      val wx = (posLon - pLast._1) / cos(rad((posLat + pLast._0) / 2))

      val vw = vx * wx + vy * wy // dot product
      if (vw == 0) { // pLast is foot point
        return Some(pLast)

      } else if (vw > 0) {
        val mv2 = vx * vx + vy * vy // square of |v|
        val s = vw / mv2
        if (s == 1.0) { // p is foot point
          return Some(p.clone)

        } else if (s < 1.0) { // foot point between pLast and p
          val t = pLast.millis + (s * (p.millis - pLast.millis)).round
          val rLat = pLast._0 + s * dLat
          val rLon = pLast._1 + s * dLon
          val rAlt = pLast._2 + s * (p._2 - pLast._2)
          pLast.set(t, rLat, rLon, rAlt)
          return Some(pLast)
        }
      }

      pLast.update(p)
    }

    None // foot point of pos outside of recorded trajectory points
  }
}

class TrajectoryDiff(val refSource: String,
                     val refTrajectory: Trajectory,

                     val diffSource: String,
                     val diffTrajectory: Trajectory,

                     val distance2DStats: SampleStats[Length],

                     val maxDistanceRefPos: GeoPosition,
                     val maxDistanceDiffPos: GeoPosition,
                     val maxDistanceTime: DateTime,

                     val minDistanceRefPos: GeoPosition,
                     val minDistanceDiffPos: GeoPosition,
                     val minDistanceTime: DateTime,

                     val angleDiffStats: SampleStats[Angle],
                     val altDiffStats: SampleStats[Length]
                      ) {
  def numberOfSamples: Int = distance2DStats.numberOfSamples
}
