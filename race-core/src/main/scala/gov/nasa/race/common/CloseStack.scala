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
package gov.nasa.race.common

/**
  * object that keeps track of AutoCloseable creation sequences and performs symmetric close on all
  * registered resources once it is closed itself
  *
  * While AutoCloseable is normally supposed to be used in try-with-resource blocks, this does not work
  * with fields (we need an explicit store instead of using the stack).
  *
  * In case our resources are just locals we can still use a single tryWithResource with a CloseStack to clean
  * up, instead of having a gazillion of nested blocks
  */
class CloseStack extends AutoCloseable {

  private var closeables = Seq.empty[AutoCloseable]

  /**
    * the trick here is to make sure we don't forget resources in case a previous one throws an
    * exception upon close
    */
  override def close = {
    while (closeables.nonEmpty){
      val h = closeables.head
      h.close
      closeables = closeables.tail
    }
  }

  def add[T <: AutoCloseable] (t: T): T = {
    closeables = t +: closeables
    t
  }
  def +=[T <: AutoCloseable] (t: T): T = add(t)

  def remove(c: AutoCloseable): Unit = {
    closeables = closeables.filter( _ != c)
  }

  def closeRemove(c: AutoCloseable): Unit = {
    c.close
    remove(c)
  }
}
