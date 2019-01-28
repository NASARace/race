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
package gov.nasa.race.common

/**
  * root type that can be mapped to a Int
  * Can be used to create generics that specify arity be means of type parameters,
  * thus allowing to compile-time check same-arity requirements
  */
sealed trait Nat {
  val toInt: Int
}

object Nat {

  final class N1 private[Nat] (val toInt: Int) extends Nat
  implicit val n1ToInt:N1 = new N1(1)

  final class N2 private[Nat] (val toInt: Int) extends Nat
  implicit val n2ToInt:N2 = new N2(2)

  final class N3 private[Nat] (val toInt: Int) extends Nat
  implicit val n3ToInt:N3 = new N3(3)

  final class N4 private[Nat] (val toInt: Int) extends Nat
  implicit val n4ToInt:N4 = new N4(4)

  final class N5 private[Nat] (val toInt: Int) extends Nat
  implicit val n5ToInt:N5 = new N5(5)

  final class N6 private[Nat] (val toInt: Int) extends Nat
  implicit val n6ToInt:N6 = new N6(6)

  final class N7 private[Nat] (val toInt: Int) extends Nat
  implicit val n7ToInt:N7 = new N7(7)

  final class N8 private[Nat] (val toInt: Int) extends Nat
  implicit val n8ToInt:N8 = new N8(8)

  final class N9 private[Nat] (val toInt: Int) extends Nat
  implicit val n9ToInt:N9 = new N9(9)

  final class N10 private[Nat] (val toInt: Int) extends Nat
  implicit val n10ToInt:N10 = new N10(10)
  
  //... and more

  def natInstance[N<:Nat](implicit nat: N) = nat
}
