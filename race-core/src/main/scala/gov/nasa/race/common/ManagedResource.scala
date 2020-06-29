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

/**
  * support for simple resource management that guarantees to call specified cleanup
  * methods for both nominal and exceptional control flow through a for comprehension.
  *
  * This is a very minimalistic replacement for the scala-arm library
  *
  * Use as in
  * {{{
  *    class A { .. def close(): Unit = .. }
  *    class B { .. def close(: Unit = .. }
  *    ...
  *       for (
  *         a <- ensureClose(new A);
  *         b <- ensureClose(new B)
  *       ) {
  *         foo(a)
  *         bar(b)
  *       }
  * }}}
  *
  * Exceptions can happen at any point of resource acquisition or use, and all resources
  * that have been acquired at this point have to be released (closed).
  *
  * The underlying for-comprehension expansion is
  * {{{
  *   for(x <- c1; y <- c2; z <-c3) {...}   ==>  c1.foreach(x => c2.foreach(y => c3.foreach(z => {...})))
  * }}}
  */

object ManagedResource {
  import scala.language.reflectiveCalls

  def ensureCleanUp[A](create: => A) (cleanUp: A=>Unit) = new ManagedResource(create,cleanUp)

  // specializations for standard cleanup methods
  // Note those need () args or otherwise Java methods won't match
  def ensureClose[A <: {def close(): Unit}] (create: => A) = new ManagedResource(create,(r:A)=>r.close())
  def ensureRelease[A <: {def release(): Unit}] (create: => A) = new ManagedResource(create,(r:A)=>r.release())
  def ensureDispose[A <: {def dispose(): Unit}] (create: => A) = new ManagedResource(create,(r:A)=>r.dispose())
}

class ManagedResource[R] (create: =>R, cleanUp: R=>Unit) {
  def foreach(f: R=>Unit): Unit = {
    var r:R = null.asInstanceOf[R]
    try {
      r = create
      f(r)
    } finally {
      if (r != null) cleanUp(r)
    }
  }
}