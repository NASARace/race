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

import java.awt.{Canvas, Color}

import javax.swing.ImageIcon

import scala.swing._

/**
  * configurable, type hierarchy aware style support for scala-swing.
  *
  * This goes beyond Swing UIManager LookAndFeel implementations because it
  * supports not only type but also use specification by means of an 'id' symbol
  * In that sense, it is a combination of configurable Look&Feel (which it could use/set)
  * and CSS
  */

class Stylist {

  // this is just to handle the NoStyle case so that we can use PartialFunctions
  // for specialized cases in the Stylist.style() implementations
  def ignoreId: PartialFunction[String,Any] = {case _ =>}
  def setIdStyle(id: String)(pf: PartialFunction[String,Any]) = {
    if (id != Style.NoStyle){
      pf.applyOrElse(id, ignoreId)
    }
  }

  val defaultFont: Font = new java.awt.Label().getFont
  def getFont (id: String): Font = defaultFont

  def getColor (id: String): Color = Color.black

  val defaultIconColor = Color.black
  def getIconColor (id: String): Color = defaultIconColor

  def sysFont: Font = defaultFont
  def sysFontMetrics = new Canvas().getFontMetrics(sysFont)
  def sysFontHeight = sysFontMetrics.getHeight
  def sysFontWidth(c: Char) = sysFontMetrics.charWidth(c)
  def lineHeight = sysFontHeight

  // this is the runtime dispatcher. Unfortunately we cannot dispatch with
  // target type specific implicit classes (i.e. at compile time) because the
  // compiler would only use the implicit for direct descendants of the respective
  // target type - scala does not use implicits for indirect base types. As a result,
  // a 'new X().style()' would not return an 'X', but rather the compiler chosen
  // base type 'style()' return type. This can lead to very confusing error messages
  // when creating domain specific UIElement subclasses.

  // obviously, order of cases does matter but the compiler will tell us about
  // unreachable code if we get it wrong

  def setStyle (o: UIElement, id: String): Unit = {
    o match {

      case c:CheckMenuItem      => style(c, id)
      case c:PopupMenu          => style(c, id)
      case c:Menu               => style(c, id)
      case c:MenuItem           => style(c, id)
      case c:MenuBar            => style(c, id)

      case c:RadioButton        => style(c, id)
      case c:CheckBox           => style(c, id)
      case c:ToggleButton       => style(c, id)
      case c:Button             => style(c, id)
      case c:AbstractButton     => style(c, id)

      case c:Slider             => style(c, id)
      case c:ProgressBar        => style(c, id)
      case c:ScrollBar          => style(c, id)

      case c:PasswordField      => style(c, id)
      case c:FormattedTextField => style(c, id)
      case c:TextField          => style(c, id)
      case c:RSTextArea         => style(c, id)
      case c:StdConsole         => style(c, id)
      case c:LogConsole         => style(c, id)
      case c:TextArea           => style(c, id)
      case c:TextPane           => style(c, id)
      case c:EditorPane         => style(c, id)
      case c:TextComponent      => style(c, id)

      case c:ListView[_]        => style(c, id)
      case c:ComboBox[_]        => style(c, id)
      case c:Table              => style(c, id)

      case c:DoubleOutputField  => style(c, id)  // our own
      case c:DigitalClock       => style(c, id)  // our own
      case c:DigitalStopWatch   => style(c, id)  // our own

      case c:CollapsiblePanel   => style(c, id)  // our own
      case c:Collapsible        => style(c, id)  // our own

      case c:BoxPanel           => style(c, id)
      case c:FlowPanel          => style(c, id)
      case c:BorderPanel        => style(c, id)
      case c:GridBagPanel       => style(c, id)
      case c:GBPanel            => style(c, id)  // our own
      case c:Panel              => style(c, id)

      case c:SplitPane          => style(c, id)
      case c:ScrollPane         => style(c, id)
      case c:AWTWrapper         => style(c, id)
      case c:Filler             => style(c, id)
      case c:Separator          => style(c, id)

      case c:DigitalClock#ClockLabel => style(c, id)  // our own
      case c:DigitalStopWatch#StopWatchLabel => style(c, id)   // our own
      case c:MessageArea        => style(c, id) // our own
      case c:Label              => style(c, id)

      case c:Component          => style(c, id)
      case c:Frame              => style(c, id)
      case c:Window             => style(c, id)

      case c:UIElement          => style(c, id)
      case _                    =>
    }
  }

  // the default versions implement the target type hierarchy (in terms of style attributes)
  // While this should match the target hierarchy, we can introduce pseudo hierarchies such as for GBPanel
  // order should not matter

  def style (c: ToggleButton, id: String)                        : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: RadioButton, id: String)                         : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: CheckBox, id: String)                            : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: AbstractButton, id: String)                      : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Button, id: String)                              : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: ComboBox[_], id: String)                         : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Menu, id: String)                                : Unit = style(c.asInstanceOf[Component], id)
  def style (c: MenuItem, id: String)                            : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: CheckMenuItem, id: String)                       : Unit = style(c.asInstanceOf[MenuItem], id)
  def style (c: PopupMenu, id: String)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: MenuBar, id: String)                             : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ScrollBar, id: String)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Slider, id: String)                              : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ProgressBar, id: String)                         : Unit = style(c.asInstanceOf[Component], id)
  def style (c: TextField, id: String)                           : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: StdConsole, id: String)                          : Unit = style(c.asInstanceOf[TextPane], id)
  def style (c: LogConsole, id: String)                          : Unit = style(c.asInstanceOf[TextPane], id)
  def style (c: TextPane, id: String)                            : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: EditorPane, id: String)                          : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: PasswordField, id: String)                       : Unit = style(c.asInstanceOf[TextField], id)
  def style (c: FormattedTextField, id: String)                  : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: TextArea, id: String)                            : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: RSTextArea, id: String)                          : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: TextComponent, id: String)                       : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ListView[_], id: String)                         : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Table, id: String)                               : Unit = style(c.asInstanceOf[Component], id)
  def style (c: BoxPanel, id: String)                            : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: FlowPanel, id: String)                           : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: GridPanel, id: String)                           : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: GBPanel, id: String)                             : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: GridBagPanel, id: String)                        : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: BorderPanel, id: String)                         : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: SplitPane, id: String)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ScrollPane, id: String)                          : Unit = style(c.asInstanceOf[Component], id)

  def style (c: DoubleOutputField, id: String)                   : Unit = style(c.asInstanceOf[FlowPanel], id)
  def style (c: AWTWrapper, id: String)                          : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Separator, id: String)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Filler, id: String)                              : Unit = style(c.asInstanceOf[Component], id)
  def style (c: DigitalClock, id: String)                        : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: DigitalClock#ClockLabel, id: String)             : Unit = style(c.asInstanceOf[Label], id)
  def style (c: DigitalStopWatch, id: String)                    : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: DigitalStopWatch#StopWatchLabel, id: String)     : Unit = style(c.asInstanceOf[Label], id)
  def style (c: MessageArea, id: String)                         : Unit = style(c.asInstanceOf[Label], id)
  def style (c: CollapsiblePanel, id: String)                    : Unit = style(c.asInstanceOf[GBPanel], id)
  def style (c: Collapsible, id: String)                         : Unit = style(c.asInstanceOf[BorderPanel], id)

  def style (c: Label, id: String)                               : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Component, id: String)                           : Unit = style(c.asInstanceOf[UIElement], id)
  def style (c: Panel, id: String)                               : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Window, id: String)                              : Unit = style(c.asInstanceOf[UIElement], id)
  def style (c: Frame, id: String)                               : Unit = style(c.asInstanceOf[Window], id)
  def style (c: UIElement, id: String)                           : Unit = {}
}


object Style {
  final val NoStyle = ""
  final val undefinedIcon = new ImageIcon()

  private var _stylist: Stylist = initStyle
  def stylist = _stylist
  
  def setStyle (s: Stylist) = {
    _stylist = s
  }

  def initStyle = {
    var clsName = System.getProperty("race.swing.style")
    if (clsName == null) clsName = "gov.nasa.race.swing.RaceDefaultStyle"
    try {
      Class.forName(clsName).getDeclaredConstructor().newInstance().asInstanceOf[Stylist]
    } catch {
      case t: Throwable => new Stylist {} // 2do - some error message would be in order
    }
  }

  //--- the API

  def getIconColor (id: String) = _stylist.getIconColor(id)
  def getFont (id: String) = _stylist.getFont(id)
  def getColor (id: String) = _stylist.getColor(id)

  def getSysFont: Font = _stylist.sysFont
  def getSysFontHeight = _stylist.sysFontHeight
  def getSysFontWidth(c: Char) = _stylist.sysFontWidth(c)

  implicit class Styled[C<:UIElement] (c:C) {
    def styled (id:String=NoStyle): C = { stylist.setStyle(c, id); c}
    def defaultStyled: C = { stylist.setStyle(c,NoStyle); c}
  }
}




