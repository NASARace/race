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

import java.awt._
import java.awt.event.{KeyEvent, MouseEvent, MouseListener, KeyListener}

import javax.swing._
import javax.swing.border.{BevelBorder, EtchedBorder, Border}
import javax.swing.plaf.basic.BasicButtonUI

object ButtonUI {
  var focusColor: Color = getFocusColor  // can be directly set by Style implementation

  def getFocusColor = {
    var clr = UIManager.getColor("Focus.color") // OS X
    if (clr == null) clr = UIManager.getColor("Button.focus")
    if (clr == null) clr = Color.BLUE // we just need some non-null value
    clr
  }
}
import ButtonUI._

class ButtonUI extends BasicButtonUI with MouseListener with KeyListener {

  //--- set upon install
  var normalBorder: Border = _
  var pressedBorder: Border = _
  var focusBorder: Border = _

  var normalBackground: Color = _
  var normalForeground: Color = _
  var pressedBackground: Color = _
  var activeForeground: Color = _

  var pressed: Boolean = false

  override def installUI(c: JComponent): Unit = {
    c.addMouseListener(this)
    //c.addKeyListener(this)

    val bColor = c.getBackground
    val brightColor = bColor.brighter.brighter
    val darkColor = bColor.darker.darker

    normalBorder = new EtchedBorder(EtchedBorder.LOWERED, brightColor, darkColor)
    pressedBorder = new BevelBorder(BevelBorder.LOWERED, brightColor, darkColor)
    focusBorder = new EtchedBorder(EtchedBorder.LOWERED, focusColor.brighter, focusColor.darker)

    normalBackground = c.getBackground
    normalForeground = c.getForeground
    pressedBackground = normalBackground.brighter
    activeForeground = normalForeground.brighter

    c.setBorder(normalBorder)
  }

  override def uninstallUI(c: JComponent): Unit = {
    c.removeMouseListener(this)
    c.removeKeyListener(this)
  }

  //--- the overridden painting

  override def paint(g: Graphics, c: JComponent): Unit = {
    val b = c.asInstanceOf[AbstractButton]

    val border = if (pressed) normalBorder else pressedBorder
    val bg = if (pressed) normalBackground else pressedBackground
    val fg = normalForeground

    val d = b.getSize()
    if (b.isOpaque){
      g.setColor(bg)
      g.fillRect(0, 0, d.width, d.height)
    }
    border.paintBorder(b, g, 0, 0, d.width, d.height)

    val icon = b.getIcon
    if (icon != null) {
      val w = icon.getIconWidth
      val h = icon.getIconHeight
      val x = (d.width - w) / 2
      val y = (d.height - h) / 2
      icon.paintIcon(c,g,x,y)
    }

    val text = b.getText
    if (text.length > 0) {
      g.setFont(b.getFont)
      val fm = g.getFontMetrics
      val x = (d.width - fm.stringWidth(text)) / 2
      val y = (d.height + fm.getAscent()) / 2
      g.setColor(fg)
      g.drawString(text, x, y)
    }
  }


  //--- the size computation
  def grow (dim: Dimension, insets: Insets) = {
    if (insets != null) {
      dim.setSize(dim.width + insets.left + insets.right,
                  dim.height + insets.top + insets.bottom)
    }
  }

  override def getPreferredSize (c: JComponent): Dimension = {
    val b = c.asInstanceOf[AbstractButton]
    val prefSize = super.getPreferredSize(b) // this doesn't properly handle margins

    grow( prefSize, b.getMargin)
    if (normalBorder != null) grow(prefSize, normalBorder.getBorderInsets(c))
    prefSize
  }

  //--- MouseListener interface
  var lastPressedTimestamp: Long = 0
  var shouldDiscardRelease: Boolean = false

  def processMousePressed (e: MouseEvent, b: AbstractButton): Unit = {
    if (SwingUtilities.isLeftMouseButton(e) ) {
      if(b.contains(e.getX(), e.getY())) {
        val multiClickThreshhold = b.getMultiClickThreshhold()
        val lastTime = lastPressedTimestamp

        lastPressedTimestamp = e.getWhen()
        val  currentTime = lastPressedTimestamp
        if (lastTime != -1 && currentTime - lastTime < multiClickThreshhold) {
          shouldDiscardRelease = true

        } else {
          val model = b.getModel()
          if (model.isEnabled()) {
            if (!model.isArmed()) {
              model.setArmed(true)
            }
            model.setPressed(true)
            if (!b.hasFocus() && b.isRequestFocusEnabled()) {
              b.requestFocus()
            }
          }
        }
      }
    }
  }

  def processMouseReleased (e: MouseEvent, b: AbstractButton): Unit = {
    if (SwingUtilities.isLeftMouseButton(e)) {
      if (shouldDiscardRelease) {
        shouldDiscardRelease = false
      } else {
        val model = b.getModel()
        model.setPressed(false)
        model.setArmed(false)
      }
    }
  }


  override def mouseClicked (e: MouseEvent): Unit = {}

  override def mousePressed (e: MouseEvent): Unit = {
    val b = e.getComponent().asInstanceOf[AbstractButton]
    pressed = true
    val d = b.getSize()
    b.paintImmediately(0,0,d.width,d.height)
    Thread.sleep(100) // make it observable

    processMousePressed(e,b)
  }

  override def mouseReleased (e: MouseEvent): Unit = {
    val b = e.getComponent().asInstanceOf[AbstractButton]
    pressed = false
    b.repaint()

    processMouseReleased(e,b)
  }

  override def mouseEntered (e: MouseEvent): Unit = {
    val c = e.getComponent().asInstanceOf[JComponent]
    c.setForeground(activeForeground)
    c.repaint()
  }

  override def mouseExited (e: MouseEvent): Unit = {
    val c = e.getComponent().asInstanceOf[JComponent]
    c.setForeground(normalForeground)
    c.repaint()
  }

  //--- KeyEvent listener
  override def keyTyped (e: KeyEvent): Unit = {}

  override def keyPressed (e: KeyEvent): Unit = {
    val code = e.getKeyCode
    if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE) {
      val c = e.getComponent().asInstanceOf[JComponent]
      c.setBorder(pressedBorder)
      c.setBackground(pressedBackground)
      c.repaint()
    }
  }

  override def keyReleased (e: KeyEvent): Unit = {
    val code = e.getKeyCode
    if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE) {
      val c = e.getComponent().asInstanceOf[JComponent]
      c.setBorder(normalBorder)
      c.setBackground(normalBackground)
      c.repaint()
    }
  }
}