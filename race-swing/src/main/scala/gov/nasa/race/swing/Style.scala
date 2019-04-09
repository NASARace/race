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

import java.awt.Color
import javax.swing.{Icon, ImageIcon}

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
  def ignoreId: PartialFunction[Symbol,Any] = {case _ =>}
  def setIdStyle(id: Symbol)(pf: PartialFunction[Symbol,Any]) = {
    if (id != Style.NoStyle){
      pf.applyOrElse(id, ignoreId)
    }
  }

  def getIcon (id: Symbol): Icon = Style.undefinedIcon

  val defaultFont = new java.awt.Label().getFont
  def getFont (id: Symbol): Font = defaultFont

  def getColor (id: Symbol): Color = Color.black

  // this is the runtime dispatcher. Unfortunately we cannot dispatch with
  // target type specific implicit classes (i.e. at compile time) because the
  // compiler would only use the implicit for direct descendants of the respective
  // target type - scala does not use implicits for indirect base types. As a result,
  // a 'new X().style()' would not return an 'X', but rather the compiler chosen
  // base type 'style()' return type. This can lead to very confusing error messages
  // when creating domain specific UIElement subclasses.

  // obviously, order of cases does matter but the compiler will tell us about
  // unreachable code if we get it wrong

  def setStyle (o: UIElement, id: Symbol): Unit = {
    o match {

      case c:Separator          => style(c, id)
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

  def style (c: ToggleButton, id: Symbol)                        : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: RadioButton, id: Symbol)                         : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: CheckBox, id: Symbol)                            : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: AbstractButton, id: Symbol)                      : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Button, id: Symbol)                              : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: ComboBox[_], id: Symbol)                         : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Menu, id: Symbol)                                : Unit = style(c.asInstanceOf[Component], id)
  def style (c: MenuItem, id: Symbol)                            : Unit = style(c.asInstanceOf[AbstractButton], id)
  def style (c: CheckMenuItem, id: Symbol)                       : Unit = style(c.asInstanceOf[MenuItem], id)
  def style (c: PopupMenu, id: Symbol)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: MenuBar, id: Symbol)                             : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ScrollBar, id: Symbol)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Slider, id: Symbol)                              : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ProgressBar, id: Symbol)                         : Unit = style(c.asInstanceOf[Component], id)
  def style (c: TextField, id: Symbol)                           : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: StdConsole, id: Symbol)                          : Unit = style(c.asInstanceOf[TextPane], id)
  def style (c: LogConsole, id: Symbol)                          : Unit = style(c.asInstanceOf[TextPane], id)
  def style (c: TextPane, id: Symbol)                            : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: EditorPane, id: Symbol)                          : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: PasswordField, id: Symbol)                       : Unit = style(c.asInstanceOf[TextField], id)
  def style (c: FormattedTextField, id: Symbol)                  : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: TextArea, id: Symbol)                            : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: RSTextArea, id: Symbol)                          : Unit = style(c.asInstanceOf[TextComponent], id)
  def style (c: TextComponent, id: Symbol)                       : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ListView[_], id: Symbol)                         : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Table, id: Symbol)                               : Unit = style(c.asInstanceOf[Component], id)
  def style (c: BoxPanel, id: Symbol)                            : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: FlowPanel, id: Symbol)                           : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: GridPanel, id: Symbol)                           : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: GBPanel, id: Symbol)                             : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: GridBagPanel, id: Symbol)                        : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: BorderPanel, id: Symbol)                         : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: SplitPane, id: Symbol)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: ScrollPane, id: Symbol)                          : Unit = style(c.asInstanceOf[Component], id)

  def style (c: DoubleOutputField, id: Symbol)                   : Unit = style(c.asInstanceOf[FlowPanel], id)
  def style (c: AWTWrapper, id: Symbol)                          : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Filler, id: Symbol)                              : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Separator, id: Symbol)                           : Unit = style(c.asInstanceOf[Component], id)
  def style (c: DigitalClock, id: Symbol)                        : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: DigitalClock#ClockLabel, id: Symbol)             : Unit = style(c.asInstanceOf[Label], id)
  def style (c: DigitalStopWatch, id: Symbol)                    : Unit = style(c.asInstanceOf[Panel], id)
  def style (c: DigitalStopWatch#StopWatchLabel, id: Symbol)     : Unit = style(c.asInstanceOf[Label], id)
  def style (c: MessageArea, id: Symbol)                         : Unit = style(c.asInstanceOf[Label], id)
  def style (c: CollapsiblePanel, id: Symbol)                    : Unit = style(c.asInstanceOf[GBPanel], id)
  def style (c: Collapsible, id: Symbol)                         : Unit = style(c.asInstanceOf[BorderPanel], id)

  def style (c: Label, id: Symbol)                               : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Component, id: Symbol)                           : Unit = style(c.asInstanceOf[UIElement], id)
  def style (c: Panel, id: Symbol)                               : Unit = style(c.asInstanceOf[Component], id)
  def style (c: Window, id: Symbol)                              : Unit = style(c.asInstanceOf[UIElement], id)
  def style (c: Frame, id: Symbol)                               : Unit = style(c.asInstanceOf[Window], id)
  def style (c: UIElement, id: Symbol)                           : Unit = {}
}


object Style {
  final val NoStyle = 'None
  final val undefinedIcon = new ImageIcon()

  private var _stylist: Stylist = initStyle
  def stylist = _stylist
  
  def setStyle (s: Stylist) = {
    _stylist = s
  }

  def initStyle = {
    var clsName = System.getProperty("race.swing.style")
    if (clsName == null) clsName = "gov.nasa.race.ww.RaceDefaultStyle" // FIXME
    try {
      Class.forName(clsName).getDeclaredConstructor().newInstance().asInstanceOf[Stylist]
    } catch {
      case t: Throwable => new Stylist {} // 2do - some error message would be in order
    }
  }

  //--- the API

  def getIcon (id: Symbol) = _stylist.getIcon(id)
  def getFont (id: Symbol) = _stylist.getFont(id)
  def getColor (id: Symbol) = _stylist.getColor(id)

  implicit class Styled[C<:UIElement] (c:C) {
    def styled (id:Symbol=NoStyle): C = { stylist.setStyle(c, id); c}
  }
}




