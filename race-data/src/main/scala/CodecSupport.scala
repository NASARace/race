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

package gov.nasa.race.data

import org.joda.time.DateTime
import scodec._
import scodec.bits._
import java.nio.ByteBuffer

import squants.motion.{Velocity, UsMilesPerHour}
import squants.space.{Length, Feet, Angle, Degrees}

// <2do> pcm: these could be probably unified with another type parameter
// for the primitive, but we keep it close to scodec for now

object CodecSupport {

  /**
   * base class to simplify scodec.Codec creation for non-case class types that
   * can be converted to/from Doubles (e.g. to create codecs for squants types
   * such as Angle)
   */
  class DoubleScodec[T](name: String, toDouble: (T) => Double, fromDouble: (Double) => T, ordering: ByteOrdering)
    extends Codec[T] {
    private val byteOrder = ordering.toJava

    override def sizeBound = SizeBound.exact(64)

    override val toString = name

    def encode(t: T): Attempt[BitVector] = {
      val value = toDouble(t)
      val buffer = ByteBuffer.allocate(8).order(byteOrder).putDouble(value)
      buffer.flip()
      Attempt.successful(BitVector.view(buffer))
    }

    override def decode(buffer: BitVector) = {
      buffer.acquire(64) match {
        case Left(e) => Attempt.failure(Err.insufficientBits(64, buffer.size))
        case Right(b) =>
          val d = ByteBuffer.wrap(b.toByteArray).order(byteOrder).getDouble
          Attempt.successful(DecodeResult(fromDouble(d), buffer.drop(64)))
      }
    }
  }

  val angle = new DoubleScodec[Angle]("angle", (a)=>a.toDegrees, (d)=>Degrees(d), ByteOrdering.BigEndian)
  val speed = new DoubleScodec[Velocity]("speed", (v)=>v.toUsMilesPerHour, (d)=>UsMilesPerHour(d), ByteOrdering.BigEndian)
  val length = new DoubleScodec[Length]("length", (v)=>v.toFeet, (d)=>Feet(d), ByteOrdering.BigEndian)

  /**
   * base class to simplify scodec.Codec creation for non-case class types that
   * can be converted to/from Long (e.g. to create codecs for types such as
   * org.joda.time.DateTime
   */
  class LongScodec[T](name: String, toLong: (T) => Long, fromLong: (Long) => T, ordering: ByteOrdering)
    extends Codec[T] {
    private val byteOrder = ordering.toJava

    override def sizeBound = SizeBound.exact(64)

    override val toString = name

    def encode(t: T): Attempt[BitVector] = {
      val value = toLong(t)
      val buffer = ByteBuffer.allocate(8).order(byteOrder).putLong(value)
      buffer.flip()
      Attempt.successful(BitVector.view(buffer))
    }

    override def decode(buffer: BitVector) = {
      buffer.acquire(64) match {
        case Left(e) => Attempt.failure(Err.insufficientBits(64, buffer.size))
        case Right(b) =>
          val d = ByteBuffer.wrap(b.toByteArray).order(byteOrder).getLong
          Attempt.successful(DecodeResult(fromLong(d), buffer.drop(64)))
      }
    }
  }

  val datetime = new LongScodec[DateTime]("datetime", (t)=>t.getMillis, (l)=>new DateTime(l), ByteOrdering.BigEndian)
}