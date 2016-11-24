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
package gov.nasa.race.swing

import javax.swing.event.{AncestorEvent, AncestorListener}

import scala.swing.{Component, Publisher}
import scala.swing.event.Event

case class AncestorAdded(e: AncestorEvent) extends Event
case class AncestorRemoved(e: AncestorEvent) extends Event
case class AncestorMoved(e: AncestorEvent) extends Event

/**
  * mixin that allows to use AncestorXX cases in reactions
  */
trait AncestorObservable extends Publisher {
  self: Component =>

  peer.addAncestorListener( new AncestorListener(){
    override def ancestorRemoved(e: AncestorEvent): Unit = publish(AncestorRemoved(e))
    override def ancestorMoved(e: AncestorEvent): Unit = publish(AncestorMoved(e))
    override def ancestorAdded(e: AncestorEvent): Unit = publish(AncestorAdded(e))
  })
}
