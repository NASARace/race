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

/**
 * a scala-swing wrapper for native AWT Components, which can't be
 * directly added to scala swing UIs since it depends on a JComponent interface
 */

import java.awt.{Component => AWTComponent}
import java.awt.BorderLayout
import javax.swing.JPanel
import scala.swing.Component

class AWTWrapper (awtComponent: AWTComponent) extends Component {

  override lazy val peer = new JPanel(new BorderLayout) {
    add( awtComponent, BorderLayout.CENTER)
  }
}
