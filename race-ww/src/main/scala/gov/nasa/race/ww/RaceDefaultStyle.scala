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

import gov.nasa.race.swing._
import java.awt.Color._
import java.awt.{Color, Font, Button => _, Component => _, Label => _, Panel => _, ScrollPane => _, TextComponent => _, TextField => _, _}

import javax.swing.UIManager
import javax.swing.border._
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.text.StyleConstants
import org.fife.ui.rsyntaxtextarea.Theme

import scala.swing.{Alignment, Button, CheckBox, ComboBox, Component, FlowPanel, Label, ListView, Panel, RadioButton, ScrollPane, TextComponent, TextField, TextPane, UIElement}

/**
 * default Swing component styles for Race
 */
class RaceDefaultStyle extends Stylist {

  val preferredConsoleWidth = scaledSize(360)

  val background = DARK_GRAY
  val foreground = WHITE
  val selectionBackground = new Color(0,32,127)
  val focusColor = Color.BLUE
  val separatorColor = new Color(42,42,42)

  val sysFont = new Font("lucida grande", Font.PLAIN, scaledSize(12))
  val txtFont = new Font("Monospaced", Font.PLAIN, scaledSize(12))

  val hiInner = background.brighter
  val hiOuter = hiInner.brighter
  val shadowInner = background.darker
  val shadowOuter = shadowInner.darker

  val sysFontMetrics = new Canvas().getFontMetrics(sysFont)
  val lineHeight = sysFontMetrics.getHeight

  def loweredBevelBorder = new BevelBorder(BevelBorder.LOWERED,
                                    hiOuter, hiInner, shadowOuter, shadowInner)

  def raisedBevelBorder = new BevelBorder(BevelBorder.RAISED,
                                    hiOuter, hiInner, shadowOuter, shadowInner)

  // this does unfortunately not work with the standard Mac Plaf
  UIManager.put("ScrollPane.background", background)
  UIManager.put("ScrollBar.track", background)
  UIManager.put("ScrollBar.background", background)
  UIManager.put("ScrollBar.thumb", GRAY)
  UIManager.put("ScrollBar.width", scaledSize(12))

  UIManager.put("SplitPane.background", background)
  UIManager.put("Focus.color", focusColor)

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
        c.border = new CompoundBorder(
          new EmptyBorder(scaledSize(7),scaledSize(5),scaledSize(5),scaledSize(5)),
          new MatteBorder(scaledSize(1),scaledSize(0),scaledSize(0),scaledSize(0),LIGHT_GRAY)
        )
    }
  }

  override def style (c: Separator, id: Symbol) = {
    super.style(c,id)

    c.preferredSize = scaledDimension(7,7)
    var clr = separatorColor
    setIdStyle(id) {
      case 'panel => clr = GRAY
    }
    c.border = new CompoundBorder( new EmptyBorder(scaledSize(3),scaledSize(3),scaledSize(3),scaledSize(3)), new LineBorder(clr))
  }

  override def style (c: Panel, id: Symbol) = {
    super.style(c,id)
    //c.opaque = true

    setIdStyle(id) {
      case 'console =>
        //c.border = new EmptyBorder(5,5,5,5)
        //c.preferredSize = (preferredConsoleWidth, 1400) // this would force the v-scroll bar
      case 'collapsible =>
        c.border = new EmptyBorder(scaledSize(12),scaledSize(5),scaledSize(5),scaledSize(10))  // top,left,bottom,right (compensate for v-scroll)
      case 'consolePanel =>
        c.border = new EmptyBorder(scaledSize(5),scaledSize(5),scaledSize(5),scaledSize(5))
      case 'layerInfo =>
        c.background = BLACK
        c.border = new EmptyBorder(scaledSize(2),scaledSize(2),scaledSize(2),scaledSize(2))
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
        c.vGap = scaledSize(10)
        c.hGap = scaledSize(10)
    }
  }

  override def style (c: GBPanel, id: Symbol) = {
    super.style(c,id)
    setIdStyle(id) {
      case 'fieldGrid =>
        c.background = BLACK
        //c.minimumSize = (300,lineHeight * 3)
    }
  }

  override def style (c: ScrollPane, id: Symbol) = {
    super.style(c,id)
    //c.opaque = true
    c.border = loweredBevelBorder

    // normal color setting doesn't work for scrollbars
    c.peer.getVerticalScrollBar.setUI(new BasicScrollBarUI)
    c.peer.getHorizontalScrollBar.setUI(new BasicScrollBarUI)

    setIdStyle(id) {
      case 'verticalAsNeeded =>
        c.horizontalScrollBarPolicy = ScrollPane.BarPolicy.Never
    }
  }

  override def style (c: ListView[_], id: Symbol) = {
    super.style(c,id)
    //c.minimumSize = (300,lineHeight * 4)

    setIdStyle(id) {
      case 'layerList | 'itemList =>
        c.background = BLACK
        c.selectionBackground = selectionBackground
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
    //c.preferredSize = scaledDimension(c.preferredSize)
    c.margin = new Insets(scaledSize(2),scaledSize(8),scaledSize(2),scaledSize(8))
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

    if (id == Style.NoStyle) {
      c.peer.setUI( new CheckBoxUI)
    } else {
      setIdStyle(id) {
        case 'collapseButton =>
          c.selectedIcon = ArrowIcon(Direction.South, scaledSize(12), foreground, 1, Some(Color.green))
          c.icon = ArrowIcon(Direction.East, scaledSize(12), foreground, 1, Some(Color.red))
      }
    }
  }

  override def style (c: RadioButton, id: Symbol) = {
    super.style(c, id)
    c.opaque = false
    c.peer.setUI( new RadioButtonUI)
  }

  override def style (c: TextComponent, id: Symbol) = {
    super.style(c, id)

    c.background = if (c.editable) BLACK else DARK_GRAY
  }

  override def style (c: TextPane, id: Symbol) = {
    super.style(c, id)
    setIdStyle(id) {
      case 'console =>
        c.font = new Font("Monospaced", Font.PLAIN, scaledSize(12))
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
    c.preferredSize = (prefSize.width, (prefSize.height * 1.2).toInt)
    c.border = loweredBevelBorder
    c.caret.color = Color.red

    setIdStyle(id) {
      case 'numField =>
        c.foreground = GREEN
        c.font = txtFont

      case 'stringField =>
        c.foreground = GREEN

      case 'queryField =>
        c.foreground = YELLOW
    }
  }

  override def style (c: Label, id: Symbol) = {
    super.style(c,id)
    setIdStyle(id) {
      case 'layerCategory =>
        c.font = new Font("lucida grande", Font.ITALIC, scaledSize(10))
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
      case 'fixedHeader =>
        c.foreground = LIGHT_GRAY
        c.font = txtFont
        c.horizontalAlignment = Alignment.Left
        c.opaque = false
        //c.border = new LineBorder(LIGHT_GRAY)
      case 'fixedFieldValue =>
        c.foreground = GREEN
        c.font = txtFont
        c.horizontalAlignment = Alignment.Left
      case 'labelFor =>
        c.verticalAlignment = Alignment.Center
        c.horizontalAlignment = Alignment.Right
      case 'title =>
        c.horizontalAlignment = Alignment.Left
        c.font = new Font("lucida grande", Font.BOLD, scaledSize(13))
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
        c.font = new Font("Monospaced", Font.BOLD, scaledSize(16))
      case 'date =>
        c.horizontalAlignment = Alignment.Left
        c.font = new Font("sans-serif", Font.BOLD, scaledSize(12))
    }
  }

  override def style (c: DigitalClock, id: Symbol) = styleTimePanel(c, id)
  override def style (c: DigitalClock#ClockLabel, id: Symbol) = styleTimeLabel(c,id)
  override def style (c: DigitalStopWatch, id: Symbol) = styleTimePanel(c, id)
  override def style (c: DigitalStopWatch#StopWatchLabel, id: Symbol) = styleTimeLabel(c,id)

  //--- icons, fonts & colors

  val flightIconColor = Color.white
  val flightBlankIcon = Images.blankFlightIcon
  val flightCenteredIcon = Images.getFlightCenteredIcon(flightIconColor)
  val flightHiddenIcon = Images.getFlightHiddenIcon(flightIconColor)
  val flightPathIcon = Images.getFlightPathIcon(flightIconColor)
  val flightInfoIcon = Images.getFlightInfoIcon(flightIconColor)
  val flightMarkIcon = Images.getFlightMarkIcon(flightIconColor)

  override def getIcon (id: Symbol) = {
    id match {
      case 'flightNone => flightBlankIcon
      case 'flightCentered => flightCenteredIcon
      case 'flightHidden => flightHiddenIcon
      case 'flightPath => flightPathIcon
      case 'flightInfo => flightInfoIcon
      case 'flightMark => flightMarkIcon
      case other => super.getIcon(id)
    }
  }

  val balloonFont = txtFont

  override def getFont (id: Symbol) = {
    id match {
      case 'balloonText => txtFont
      case other => sysFont
    }
  }

  val balloonTextColor = Color.cyan
  val balloonBackgroundColor = new Color(0, 0, 0, 0.4f)
  val balloonBorderColor = Color.cyan.darker.darker

  override def getColor (id: Symbol) = {
    id match {
      case 'balloonText => balloonTextColor
      case 'balloonBackground => balloonBackgroundColor
      case 'balloonBorder => balloonBorderColor
      case other => super.getColor(id)
    }
  }
}
