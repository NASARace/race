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
import gov.nasa.race.uom.{Angle, Length, OnlineAngleStats, OnlineLengthStats}

object TrajectoryDiff {

  /**
    * calculate differences between two trajectories
    *
    * @param refTrajectory       the reference trajectory that will be interpolated
    * @param diffTrajectory      the trajectory for which the deviation to refTrajectory will be calculated
    * @param getInterpolant  function to create a interpolator for refTrajectory
    * @param computeDistance2D   function to compute 2D (lat/lon) distance between two trajectory points
    * @param computeDiffAngle    function to compute angle between two trajectory points
    * @return
    */
  def calculate (refTrajectory: Trajectory,
                 diffTrajectory: Trajectory,
                 getInterpolant: Trajectory=>TInterpolant[N3,TDP3],
                 areaFilter: GeoPosition=>Boolean,
                 computeDistance2D: (GeoPosition,GeoPosition)=>Length,
                 computeDiffAngle: (GeoPosition,GeoPosition)=>Angle
                 ): Option[TrajectoryDiff] = {
    if (refTrajectory.size < 2) return None // nothing we can interpolate

    val refIntr = getInterpolant(refTrajectory)

    val dist2DStats = new OnlineLengthStats
    val altDiffStats = new OnlineLengthStats
    val angleDiffStats = new OnlineAngleStats

    diffTrajectory.foreach(new TDP3){ p=>
      if (areaFilter(p) && refTrajectory.includesDate(p.epochMillis)) {
        val pRef = refIntr.eval(p.millis)

        dist2DStats += computeDistance2D(pRef, p)
        altDiffStats += (pRef.altitude - p.altitude)
        angleDiffStats += computeDiffAngle(pRef, p)
      }
    }

    if (dist2DStats.numberOfSamples > 0) {
      Some(new TrajectoryDiff(refTrajectory, diffTrajectory, dist2DStats, angleDiffStats, altDiffStats))
    } else None
  }
}

class TrajectoryDiff(val refTrajectory: Trajectory,
                     val diffTrajectory: Trajectory,
                     val distance2DStats: SampleStats[Length],
                     val angleDiffStats: SampleStats[Angle],
                     val altDiffStats: SampleStats[Length]
                    ) {
  def numberOfSamples: Int = distance2DStats.numberOfSamples
}
