/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.util

import javax.sound.sampled._

/**
  * collection of sound related functions
  */
object SoundUtils {

  final val SampleRate = 8000f
  val af = new AudioFormat(SampleRate, 8, 1, true, false)
  val buf = new Array[Byte](1)


  def tone (hz: Int, msec: Int, volume: Double = 1.0): Unit = synchronized {
    val sdl = AudioSystem.getSourceDataLine(af)
    sdl.open(af)
    sdl.start

    val c1 = 2.0 * Math.PI / (SampleRate / hz)
    val c2 = 127.0 * volume
    var imax = msec*8
    var i = 0

    while (i < imax) {
      buf(0) = (Math.sin(i * c1) * c2).toByte
      sdl.write(buf,0,1)
      i += 1
    }

    sdl.drain
    sdl.stop
    sdl.close
  }
}
