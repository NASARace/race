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
package gov.nasa.race.common

import gov.nasa.race._
import scala.concurrent.duration.{FiniteDuration, _}

/**
  * an extrapolator that uses double exponential smoothing for irregular time series
  * based on a modified Holt Winters algorithm (see http://www.mff.cuni.cz/veda/konference/wds/proc/pdf08/WDS08_111_m5_Hanzak.pdf)
  *
  * NOTE - we need to normalize the time difference or otherwise the smoothing factors will degenerate to 1.0
  *
  * Note - adapting the weight of the trend with changing |Δt| does follow more closely but creates oscillations. Since
  * we are more interested in smoothing and probably don't get time-close observations (same source) we skip it for now
  */
class SmoothingExtrapolator (ΔtAverage: FiniteDuration = 1.second,     // average observation interval
                             α0: Double = 0.3,              // level smoothing factor seed [0..1]
                             γ0: Double = 0.1               // trend smoothing factor seed [0..1]
                            ){
  private var tlast: Long = -1    // previous observation time in msec
  private var Δtlast: Double = 0  // in sec

  private final val tscale = 10.0 ** Math.round(Math.log10(ΔtAverage.toMillis))

  private var α: Double = α0  // level smoothing factor [0..1]
  private var γ: Double = γ0  // trend smoothing factor [0..1]

  private var s: Double = 0 // level estimate
  private var m: Double = 0 // trend estimate

  def addObservation (y: Double, t: Long): Unit = {
    if (tlast < 0) { // first observation, nothing to smooth yet
      s = y
      tlast = t

    } else {
      val Δt = (t - tlast) / tscale

      if (Δt != 0) { // safe guard against duplicated observations that would cause infinity results
        //-- update smoothing factors
        α = α / (α + (1 - α) ** Δt)
        γ = γ / (γ + (1 - γ) ** Δt)
        //γ = γ / (γ + (Δtlast / Δt) * ((1 - γ) ** Δt))

        val sʹ = (1 - α) * (s + Δt * m) + α * y
        m = (1 - γ) * m + γ * (sʹ - s) / Δt
        s = sʹ

        tlast = t
        Δtlast = Δt
      }
    }
  }

  @inline final def extrapolate (t: Long): Double = s + (t-tlast) * m / tscale

  def reset: Unit = {
    tlast = -1
    Δtlast = 0
    α = α0
    γ = γ0
    s = 0
    m = 0
  }
}

