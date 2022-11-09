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

package gov.nasa.race

import scala.language.implicitConversions
import scala.reflect.ClassTag


/**
  * package `gov.nasa.race.util` contains functional extensions of external library constructs, most notably
  * standard java.* classes. Those functions are loosely coupled and are kept in library objects (singletons, e.g.
  * `FileUtils`).
  *
  * This package can also contain utility types that specialize standard interfaces and classes and are used
  * transiently (streams, tokenizers etc.)
  */
package object util {

  def acceptAll: PartialFunction[String,Unit] = {
    case _ =>
  }

  //--- Java interoperability

  // to use Scala lambdas as Java method arguments
  implicit def sfunc1Tojfunc1[A](f: (A) => Any) = new java.util.function.Function[A, Any]() {
    override def apply(a: A): Any = f(a)
  }

  implicit def sFunc2TojBiFunc[A, B, R](f: (A, B) => R): java.util.function.BiFunction[A, B, R] = new java.util.function.BiFunction[A, B, R]() {
    override def apply(a: A, b: B): R = f(a, b)
  }

  @inline def toArray[T <:Object : ClassTag, U <: T] (list: java.util.List[U]) = list.toArray(new Array[T](list.size))

  @inline def foreachInJavaIterable[T](jiterable: java.lang.Iterable[T])(f: T=>Unit) = {
    val it = jiterable.iterator()
    while (it.hasNext) f(it.next())
  }
}
