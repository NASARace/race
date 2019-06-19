/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import javax.swing.{DefaultComboBoxModel, JComboBox}

import scala.reflect.ClassTag
import scala.swing.ComboBox
import scala.jdk.CollectionConverters._

import java.util.Vector

/**
  * a ComboBox that uses a modifiable data model
  */
class DynamicComboBox[A: ClassTag](items: Seq[A]) extends ComboBox[A](items) {

  override lazy val peer: JComboBox[A] = {
    new JComboBox[A](new DefaultComboBoxModel[A](new Vector[A](items.asJava))) with SuperMixin
  }

  def addItem (a: A) = peer.addItem(a)
  def removeItem (a: A) = peer.removeItem(a)
}
