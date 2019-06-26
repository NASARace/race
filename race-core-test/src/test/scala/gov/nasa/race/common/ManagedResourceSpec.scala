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

import ManagedResource._
import gov.nasa.race.test.RaceSpec
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.ArrayBuffer

/**
  * unit test for ManagedResource
  */
class ManagedResourceSpec  extends AnyFlatSpec with RaceSpec {
  final val dontThrow = 0
  final val throwInCtor = 1
  final val throwInUse = 2
  final val throwInRelease = 3

  class Registry {
    val acquired = new ArrayBuffer[String]
    val released = new ArrayBuffer[String]
  }

  class TestException (msg: String) extends RuntimeException(msg)

  abstract class Resource(id: String, reg: Registry) {
    var released = false
    protected def _acquire (throwInAcquire: Boolean) = {
      if (throwInAcquire) throw new TestException(s"$id throwing in acquisition")
      reg.acquired += id
    }
    protected def _use (throwInUse: Boolean) = {
      println(s"using $id")
      if (throwInUse) throw new TestException(s"$id throwing in use")
    }
    protected def _release (throwInRelease: Boolean) = {
      if (throwInRelease) throw new TestException(s"$id throwing in release")
      released = true
      reg.released += id
      println(s"released $id")
    }
  }

  //--- our test resource types (varying release methods)
  class A (id: String, throwIn: Int)(implicit reg: Registry) extends Resource(id,reg) {
    _acquire(throwIn == throwInCtor)
    println(s"acquired $id")

    def close(): Unit = _release(throwIn == throwInRelease)
    def use = _use(throwIn == throwInUse)
  }
  class B (id: String, throwIn: Int)(implicit reg: Registry) extends Resource(id,reg) {
    _acquire(throwIn == throwInCtor)
    println(s"acquired $id")

    def release(): Unit = _release(throwIn == throwInRelease)
    def use = _use(throwIn == throwInUse)
  }

  //--- tests

  "managed resources" should "be released up to the point of last successful acquisition" in {
    implicit val reg = new Registry

    expectException[TestException] {
      for (
        a <- ensureClose(new A("A", dontThrow));
        b <- ensureRelease(new B("B", throwInCtor))
      ) {
        a.use
        b.use
      }
    } shouldBe true

    reg.acquired shouldBe ArrayBuffer("A")
    reg.released shouldBe ArrayBuffer("A")
  }
}
