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
package gov.nasa.race.common

import gov.nasa.race.uom.Angle
import gov.nasa.race.uom.Angle.{Radians, absDiff}

import java.lang.Math._

object Cartesian3 {

  def closestPointInLineSegment(p0: Cartesian3, p1: Cartesian3, p: Cartesian3): Cartesian3 = {
    val p01 = p1 - p0
    val t = - (p0 - p).dot(p01) / p01.length2

    if (t >= 1.0) p1
    else if (t <= 0) p0
    else p0 + (p01 * t)
  }

  def intersectionWithPlane (p0: Cartesian3, p1: Cartesian3, p: Cartesian3): Cartesian3 = {
    val n = (p0.cross(p1)).unit // p0-p1 plane unit normal
    val d = p.dot(n) // distance of p to p0-p1 plane
    (p + n*d)
  }
}

/**
 * something that has 3 orthogonal dimension-less coordinates
 */
sealed trait Cartesian3 {
  type Self <: Cartesian3
  type BigSelf <: BigCartesian3
  type MutSelf <: MutCartesian3
  type MutBigSelf <: MutCartesian3

  def x: Double
  def y: Double
  def z: Double

  def newSelf(x:Double,y:Double,z:Double): Self
  def newBigSelf(x:Double,y:Double,z:Double): BigSelf
  def newMutSelf(x:Double,y:Double,z:Double): MutSelf
  def newMutBigSelf(x:Double,y:Double,z:Double): MutBigSelf

  def length2: Double = x*x + y*y + z*z
  def length: Double = sqrt( x*x + y*y + z*z)

  def unit: Self = {
    val len = length
    newSelf(x/len, y/len, z/len)
  }

  def maxAbs: Double = max( max( abs(x), abs(y)), abs(z))

  def dot (o: Cartesian3): Double = {
    (x * o.x) + (y * o.y) + (z * o.z)
  }
  @inline def ⋅ (o: Cartesian3): Double = dot(o)

  def cross (o: Cartesian3): Self = {
    val a = (y * o.z) - (z * o.y)
    val b = (z * o.x) - (x * o.z)
    val c = (x * o.y) - (y * o.x)
    newSelf(a,b,c)
  }
  @inline def × (o: Cartesian3): Self = cross(o)

  def angle (o: Cartesian3): Angle = {
    Radians( acos( dot(o)/(length * o.length)))
  }

  @inline def + (o: Cartesian3): Self = newSelf(x+o.x, y+o.y, z+o.z)
  @inline def - (o: Cartesian3): Self = newSelf(x-o.x, y-o.y, z-o.z)

  @inline def * (d: Double):Self = newSelf(x*d,y*d,z*d)
  @inline def / (d: Double):Self = newSelf(x/d,y/d,z/d)

  // conversion between mut / big variants
  def toSelf: Self = newSelf(x,y,z)
  def toBig: BigSelf = newBigSelf(x,y,z)
  def toMut: MutSelf = newMutSelf(x,y,z)
  def toMutBig: MutBigSelf = newMutBigSelf(x,y,z)
}

/**
 * a mutable Cartesian3
 */
trait MutCartesian3 extends Cartesian3 {
  def x_= (v: Double): Unit
  def y_= (v: Double): Unit
  def z_= (v: Double): Unit

  @inline final def <-- (o: Cartesian3): this.type = {
    x = o.x
    y = o.y
    z = o.z
    this
  }

  @inline final def setTo(newX: Double, newY: Double, newZ: Double): Unit = {
    x = newX
    y = newY
    z = newZ
  }

  @inline final def setToCross (a: Cartesian3, b: Cartesian3): Unit = {
    x = (a.y * b.z) - (a.z * b.y)
    y = (a.z * b.x) - (a.x * b.z)
    z = (a.x * b.y) - (a.y * b.x)
  }

  @inline final def scaleToUnit: Unit = {
    val len = length
    x = (x / len)
    y = (y / len)
    z = (z / len)
  }

  def setToIntersectionWithPlane (p0: Cartesian3, p1: Cartesian3, p: Cartesian3): Unit = {
    setToCross(p0,p1)
    scaleToUnit
    val d = -p.dot(this) // distance of p to p0-p1 plane
    this *= d
    this += p
  }

  def += (o: Cartesian3): this.type = { x= x+o.x; y= y+o.y; z= z+o.z; this }
  def -= (o: Cartesian3): this.type = { x= x-o.x; y= y-o.y; z= z-o.z; this }

  def *= (d: Double): this.type = { x=x*d; y=y*d; z=z*d; this }
  def /= (d: Double): this.type = { x=x/d; y=y/d; z=z/d; this }
}

/**
 * this is essentially a homogenous version of Cartesian3
 */
trait BigCartesian3 extends Cartesian3 {
  override def length: Double = {
    val m = maxAbs
    sqrt( squared(x/m) + squared(y/m) + squared(z/m)) * m
  }

  override def dot (o: Cartesian3): Double = {
    val m = maxAbs
    val om = o.maxAbs

    ((x/m * o.x/om) + (y/m * o.y/om) + (z/m * o.z/om)) * m * om
  }

  override def cross (o: Cartesian3): Self = {
    val m = maxAbs
    val om = o.maxAbs

    val a = ((y/m * o.z/om) - (z/m * o.y/om)) * m * om
    val b = ((z/m * o.x/om) - (x/m * o.z/om)) * m * om
    val c = ((x/m * o.y/om) - (y/m * o.x/om)) * m * om
    newSelf(a,b,c)
  }
}

trait XyzBase extends Cartesian3 {
  type Self = Xyz
  type BigSelf = BigXyz
  type MutSelf = MutXyz
  type MutBigSelf = MutBigXyz

  def newSelf(a:Double,b:Double,c:Double): Self = Xyz(a,b,c)
  def newBigSelf(a:Double,b:Double,c:Double): BigSelf = BigXyz(a,b,c)
  def newMutSelf(a:Double,b:Double,c:Double): MutSelf = MutXyz(a,b,c)
  def newMutBigSelf(a:Double,b:Double,c:Double): MutBigSelf = MutBigXyz(a,b,c)
}

/**
 * dimension-less invariant version. you are on your own
 */
final case class Xyz (x: Double, y: Double, z: Double) extends XyzBase {
  override val maxAbs = super.maxAbs
}

/**
 * an Xyz with potentially large element values that might cause numeric errors
 * note this is less efficient due to the required scaling/unscaling
 */
final case class BigXyz (x: Double, y: Double, z: Double) extends XyzBase with BigCartesian3 {
  override val maxAbs = super.maxAbs
}

/**
 * mutable version of Xyz
 */
final case class MutXyz (var x: Double = 0, var y: Double = 0, var z: Double = 0) extends XyzBase with MutCartesian3 {
  @inline final def set (newX: Double, newY: Double, newZ: Double): Unit = {
    x = newX; y = newY; z = newZ
  }

  override def += (o: Cartesian3): this.type = { x+=o.x; y+=o.y; z+=o.z; this }
  override def -= (o: Cartesian3): this.type = { x-=o.x; y-=o.y; z-=o.z; this }

  override def *= (d: Double): this.type = { x*=d; y*=d; z*=d; this }
  override def /= (d: Double): this.type = { x/=d; y/=d; z/=d; this }
}

/**
 * mutable version of BigXyz
 */
final case class MutBigXyz (var x: Double = 0, var y: Double = 0, var z: Double = 0) extends XyzBase with BigCartesian3 with MutCartesian3