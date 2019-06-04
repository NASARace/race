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
package gov.nasa.race.swing

import java.awt.{Dimension, Graphics, Graphics2D, FontMetrics}

import javax.swing.{JComponent, JToggleButton}
import javax.swing.plaf.basic.BasicButtonUI

/**
  * very simplistic plaf that is more suitable for HiDPI
  */
abstract class ToggleButtonUI extends BasicButtonUI {

  protected def paintIcon(g: Graphics2D, c: JToggleButton, x: Int, y: Int, h: Int)

  override def paint(g: Graphics, c: JComponent): Unit = {
    val g2 = g.asInstanceOf[Graphics2D]
    val b = c.asInstanceOf[JToggleButton]
    val dim: Dimension = b.getSize
    val in = b.getInsets
    val txt = b.getText
    val fnt = b.getFont
    val fm = b.getFontMetrics(fnt)
    val clr = c.getForeground

    val x = dim.height
    val y = in.top + fm.getMaxAscent

    val h = fm.getHeight * 2 / 3
    val d = (dim.height - h) / 2

    //--- draw selection symbol
    paintIcon(g2,b, d, d, h)

    //--- draw text
    g2.drawString(txt, x, y)
  }

  override def getPreferredSize (c: JComponent): Dimension = {
    val b = c.asInstanceOf[JToggleButton]
    val txt = b.getText
    val fnt = b.getFont
    val fm = b.getFontMetrics(fnt)
    val in = b.getInsets

    val sw = fm.stringWidth(txt)
    val sh = fm.getHeight

    val h = sh + in.top + in.bottom
    val w = sw + h + in.left + in.right

    new Dimension(w,h)
  }
}

class RadioButtonUI extends ToggleButtonUI {

  protected def paintIcon(g: Graphics2D, c: JToggleButton, x: Int, y: Int, h: Int): Unit = {
    if (c.isSelected) {
      g.fillOval(x,y,h,h)
    } else {
      g.drawOval(x,y,h,h)
    }
  }
}

class CheckBoxUI extends ToggleButtonUI {

  protected def paintIcon(g: Graphics2D, c: JToggleButton, x: Int, y: Int, h: Int): Unit = {
    if (c.isSelected) {
      g.fillRect(x,y,h,h)
    } else {
      g.drawRect(x,y,h,h)
    }
  }
}