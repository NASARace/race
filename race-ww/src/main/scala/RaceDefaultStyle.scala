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

import java.awt.Color._
import java.awt.{Color, Cursor, Font}
import javax.swing.UIManager
import javax.swing.border._
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.text.StyleConstants

import gov.nasa.race.swing.{AWTWrapper, DigitalClock, Stylist, _}
import org.fife.ui.rsyntaxtextarea.Theme

import scala.swing._

/**
 * default Swing component styles for Race
 */
class RaceDefaultStyle extends Stylist {

  val preferredConsoleWidth = 350

  val background = DARK_GRAY
  val foreground = WHITE
  val sysFont = new Font("lucida grande", Font.PLAIN, 12)
  val txtFont = new Font("Monospaced", Font.PLAIN, 12)

  val hiInner = background.brighter
  val hiOuter = hiInner.brighter
  val shadowInner = background.darker
  val shadowOuter = shadowInner.darker

  def loweredBevelBorder = new BevelBorder(BevelBorder.LOWERED,
                                    hiOuter, hiInner, shadowOuter, shadowInner)

  def raisedBevelBorder = new BevelBorder(BevelBorder.RAISED,
                                    hiOuter, hiInner, shadowOuter, shadowInner)

  // this does unfortunately not work with the standard Mac Plaf
  UIManager.put("ScrollPane.background", background)
  UIManager.put("ScrollBar.track", background)
  UIManager.put("ScrollBar.background", background)
  UIManager.put("ScrollBar.thumb", GRAY)
  UIManager.put("ScrollBar.width", 12)

  UIManager.put("SplitPane.background", background)

  //--- component styles

  override def style (c: UIElement, id: Symbol) = {
    super.style(c,id)
    c.foreground = foreground
    c.background = background
    c.font = sysFont
  }

  override def style (c: Component, id: Symbol) = {
    super.style(c,id)

    setIdStyle(id) {
      case 'collapseBar =>
        c.border = new CompoundBorder( new EmptyBorder(7,5,5,5), new MatteBorder(1,0,0,0,LIGHT_GRAY))
    }
  }

  override def style (c: Panel, id: Symbol) = {
    super.style(c,id)
    //c.opaque = true

    setIdStyle(id) {
      case 'console =>
        c.border = new EmptyBorder(5,5,5,5)
        c.preferredSize = (preferredConsoleWidth, 1000)
      case 'collapsible =>
        c.border = new EmptyBorder(12,5,5,5)  // top,left,bottom,right
      case 'consolePanel =>
        c.border = new EmptyBorder(5,5,5,5)
      case 'layerInfo =>
        c.background = BLACK
        c.border = new EmptyBorder(2,2,2,2)
      case 'titled =>
        if (c.border != null && c.border.isInstanceOf[TitledBorder]){
          val titledBorder = c.border.asInstanceOf[TitledBorder]
          titledBorder.setTitleColor(foreground)
          titledBorder.setTitleFont(sysFont)
        }
    }
  }

  override def style (c: FlowPanel, id: Symbol) = {
    super.style(c,id)
    setIdStyle(id) {
      case 'appPanel =>
        c.vGap = 10
        c.hGap = 10
    }
  }

  override def style (c: GBPanel, id: Symbol) = {
    super.style(c,id)
    setIdStyle(id) {
      case 'fieldGrid =>
        c.background = BLACK
    }
  }

  override def style (c: ScrollPane, id: Symbol) = {
    super.style(c,id)
    //c.opaque = true
    c.border = loweredBevelBorder

    // normal color setting doesn't work for scrollbars
    c.peer.getVerticalScrollBar.setUI(new BasicScrollBarUI)
    c.peer.getHorizontalScrollBar.setUI(new BasicScrollBarUI)
  }

  override def style (c: ListView[_], id: Symbol) = {
    super.style(c,id)

    setIdStyle(id) {
      case 'layerList =>
        c.background = BLACK
    }
  }

  override def style (c: AWTWrapper, id: Symbol) = {
    super.style(c,id)
    setIdStyle(id){
      case 'world =>
        c.cursor = Cursor.getPredefinedCursor( Cursor.CROSSHAIR_CURSOR)
        c.border = loweredBevelBorder
    }
  }

  override def style (c: Button, id: Symbol) = {
    super.style(c,id)
    c.opaque = true
    c.margin = new Insets(2,8,2,8)
    c.peer.setUI(new ButtonUI) // the basic plafs are utterly useless
  }

  override def style (c: ComboBox[_], id: Symbol) = {
    super.style(c,id)
    //c.peer.setUI(new BasicComboBoxUI)
    //c.background = background
    //c.foreground = foreground
    c.foreground = BLACK
    c.background = WHITE
  }


  override def style (c: CheckBox, id: Symbol) = {
    super.style(c,id)
    c.opaque = false
    setIdStyle(id) {
      case 'collapseButton =>
        c.selectedIcon = ArrowIcon(Direction.South, 12, foreground, 1, Some(Color.green))
        c.icon = ArrowIcon(Direction.East, 12, foreground, 1, Some(Color.red))
    }
  }

  override def style (c: TextComponent, id: Symbol) = {
    super.style(c, id)

    c.background = if (c.editable) BLACK else DARK_GRAY
  }

  override def style (c: TextPane, id: Symbol) = {
    super.style(c, id)
    setIdStyle(id) {
      case 'console =>
        c.font = new Font("Monospaced", Font.PLAIN, 12)
    }
  }

  override def style (c: RSTextArea, id: Symbol) = {
    super.style(c, id)

    val theme = Theme.load(classOf[RSTextArea].getResourceAsStream("rst-dark.xml"))
    theme.apply(c.peer)
    c.peer.setCursor(Cursor.getDefaultCursor) // until we have a colored TEXT_CURSOR
  }

  override def style (c: LogConsole, id: Symbol) = {
    super.style(c, id)

    c.background = Color.black
    c.peer.setSelectionColor(Color.blue)
    StyleConstants.setForeground(c.commentStyle, Color.cyan)
    StyleConstants.setForeground(c.errorStyle, Color.red)
    StyleConstants.setForeground(c.warningStyle, Color.yellow)
    StyleConstants.setForeground(c.infoStyle, Color.white)
    StyleConstants.setForeground(c.debugStyle, Color.darkGray)
  }

  override def style (c: StdConsole, id: Symbol) = {
    super.style(c, id)

    c.background = Color.black
    c.peer.setSelectionColor(Color.blue)
    StyleConstants.setForeground(c.commentStyle, Color.cyan)
    StyleConstants.setForeground(c.errStyle, Color.red)
    StyleConstants.setForeground(c.outStyle, Color.green)
  }

  override def style (c: TextField, id: Symbol) = {
    super.style(c,id)

    val prefSize = c.preferredSize
    c.preferredSize = (prefSize.width, (prefSize.height * 1.1).toInt)
    c.border = loweredBevelBorder
    c.caret.color = Color.red

    setIdStyle(id) {
      case 'numField =>
        c.foreground = GREEN
        c.font = new Font("Monospaced", Font.BOLD, 13)

      case 'stringField =>
        c.foreground = GREEN
    }
  }

  override def style (c: Label, id: Symbol) = {
    super.style(c,id)
    setIdStyle(id) {
      case 'layerCategory =>
        c.font = new Font("lucida grande", Font.ITALIC, 10)
        c.horizontalAlignment = Alignment.Right
      case 'layerName =>
        c.foreground = GREEN
      case 'fieldName =>
        c.foreground = LIGHT_GRAY
        //c.font = txtFont
        c.horizontalAlignment = Alignment.Right
      case 'fieldValue =>
        c.foreground = GREEN
        //c.font = txtFont
        c.horizontalAlignment = Alignment.Left
      case 'labelFor =>
        c.verticalAlignment = Alignment.Center
        c.horizontalAlignment = Alignment.Right
      case 'title =>
        c.horizontalAlignment = Alignment.Left
        c.font = new Font("lucida grande", Font.BOLD, 13)
    }
  }

  override def style (c: MessageArea, id: Symbol) = {
    super.style(c,id)

    val prefSize = c.preferredSize
    c.preferredSize = (prefSize.width, (prefSize.height * 1.1).toInt)
    c.border = loweredBevelBorder
    c.horizontalAlignment = Alignment.Left
    c.foreground = GREEN
    c.alertColor = YELLOW
  }

  def styleTimePanel (c: Panel, id: Symbol) = {
    super.style(c,id)
    c.opaque = true
    c.border = loweredBevelBorder
  }
  def styleTimeLabel (c: Label, id: Symbol) = {
    super.style(c,id)
    c.opaque = true
    c.background = BLACK
    c.foreground = GREEN

    setIdStyle(id) {
      case 'time =>
        c.horizontalAlignment = Alignment.Center
        c.font = new Font("Monospaced", Font.BOLD, 16)
      case 'date =>
        c.horizontalAlignment = Alignment.Left
        c.font = new Font("sans-serif", Font.BOLD, 12)
    }
  }

  override def style (c: DigitalClock, id: Symbol) = styleTimePanel(c, id)
  override def style (c: DigitalClock#ClockLabel, id: Symbol) = styleTimeLabel(c,id)
  override def style (c: DigitalStopWatch, id: Symbol) = styleTimePanel(c, id)
  override def style (c: DigitalStopWatch#StopWatchLabel, id: Symbol) = styleTimeLabel(c,id)


}
