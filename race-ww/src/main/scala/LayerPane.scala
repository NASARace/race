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

package gov.nasa.race.ww

import java.awt.{Component => AWTComponent, Button => AWTButton, Container => AWTContainer, BorderLayout}
import javax.swing.{JPanel, JLayeredPane}

import scala.swing.Component

/**
  * a layered pane with a native root
  */
class LayerPane (awtComponent: AWTComponent) extends Component {

  override lazy val peer = new JPanel {

    val nativeContainer = new AWTContainer(){
      add(awtComponent)

      val button = new AWTButton("click")
      add(button)

      setComponentZOrder(button, getComponentCount-1)
      setComponentZOrder(awtComponent,getComponentCount-1)

      override def doLayout(): Unit = {
        val bounds = this.getBounds()
        awtComponent.setBounds(0,0,bounds.width,bounds.height)

        button.setBounds(10, bounds.height/2, 40, 20)
      }
    }

    setLayout(new BorderLayout)
    add(nativeContainer)

    override def doLayout(): Unit = {
      val bounds = this.getBounds()
      nativeContainer.setBounds(0,0,bounds.width,bounds.height)
    }
  }
}
