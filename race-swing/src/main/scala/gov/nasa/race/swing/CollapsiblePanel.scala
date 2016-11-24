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

import java.awt.event.{ActionEvent, ActionListener}
import java.awt.{BorderLayout, Color}
import javax.swing.border.EmptyBorder
import javax.swing.{JPanel, Timer}

import gov.nasa.race._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.language.postfixOps
import scala.swing._
import scala.swing.event.ButtonClicked

/**
  * object CollapsiblePanel {
  * def main (args: Array[String]): Unit = {
  * Style.setStyle(new RaceDefaultStyle)
  **
  *val top = new AppFrame {
  *title = "CollapsiblePanelDemo"
  **
  *val child1 = new BoxPanel (Orientation.Vertical){
  *border = new EmptyBorder(10,10,10,10)
  *for (i <- 1 until 5) contents += new Label(s"line $i").styled()
  *}.styled()
  **
  *
  *val listData = for (i <- 1 until 20) yield(i.toString)
  *val list = new ListView[String](listData).styled()
  *val child2 = new ScrollPane(list)
  *child2.preferredSize = new Dimension(200,150)
  **
 *val panel: CollapsiblePanel = new CollapsiblePanel().styled('layerInfo)
  *panel.add("panel 1", child1)
  *panel.add("panel 2", child2)
  **
 *contents = panel
  *size = new Dimension(300,300)
  *pack()
  *}
  *top.open()
  *}
*}
  **/

class CollapsiblePanel extends GBPanel {
  val c = new Constraints( gridx=0, fill=Fill.Horizontal, anchor=Anchor.NorthWest, weightx=1.0)
  val cFiller = c.clone.weighty(1.0)
  val filler = new Filler().styled()

  // this is reasonably brain dead - since we don't have a callback for doLayout, we have to constantly remove/add a filler
  def add (title: String, child: Component, toolTip:String= "click to collapse/expand", isExpanded: Boolean=true) = {
    if (contents.nonEmpty) layout -= filler
    layout(new Collapsible(title,child,toolTip,isExpanded).styled('collapsible)) = c
    layout(filler) = cFiller
  }

  def set (title: String, child: Component, toolTip:String= "click to collapse/expand", isExpanded: Boolean=true):Unit = {
    if (!contents.exists {
      _ match {
        case c:Collapsible if c.title == title =>
          c.setContent(child)
          child.repaint
          true
        case other => false
      }
    }) add(title,child,toolTip,isExpanded)
  }

  def expand(title: String, setExpanded: Boolean): Unit = {
    for (c <- contents) {
      ifInstanceOf[Collapsible](c) { collapsible =>
        if (collapsible.title == title) {
          collapsible.setExpanded( setExpanded)
        }
      }
    }
  }
}

/**
  * a JPanel with a title and icon to collapse/expand its content. Those are the
  * components inside a CollapsiblePanel
  */
class Collapsible (val title: String, var content: Component, toolTip:String, var isExpanded: Boolean)
                                              extends BorderPanel {
  val collapsedTitle = s"$title ..."

  background = Color.BLUE
  val titleButton = new CheckBox(title){
    border = new EmptyBorder(0, 4, 0, 0)
    tooltip = toolTip
    horizontalTextPosition = Alignment.Right
    selected = isExpanded
  }.styled('collapseButton)

  val titleBox = new BoxPanel(Orientation.Horizontal){
    contents += titleButton
    contents += new Component {}.styled('collapseBar)
  }.styled('collapseTitlebar)
  layout(titleBox) = BorderPanel.Position.North

  val contentWrapper = new ContentWrapper(content)
  peer.add(contentWrapper, BorderLayout.CENTER)
  var prefContentSize = content.preferredSize

  if (!isExpanded){
    content.visible = false
    updateTitleBar(false)
    contentWrapper.setPreferredSize(prefContentSize.width, 0)
  }

  listenTo(titleButton)
  reactions += {
    case ButtonClicked(`titleButton`) => setExpanded(titleButton.selected)
  }

  def setContent (newContent: Component) = {
    content = newContent
    prefContentSize.height = content.preferredSize.height
    contentWrapper.setContent(newContent)
  }

  def updateTitleBar (newState: Boolean) = {
    titleButton.text = if (isExpanded) title else collapsedTitle
    titleButton.selected = newState // in case this was called explicitly
  }

  def setExpanded (newState: Boolean) = {
    if (newState != isExpanded){
      isExpanded = newState
      if (!isExpanded) prefContentSize = contentWrapper.content.size // it might have changed
      updateTitleBar(newState)
      updateAnimated(newState)
    }
  }

  def updateImmediate (expand: Boolean) = {
    if (showing){
      val height = if (isExpanded) prefContentSize.height else 0
      content.visible = isExpanded
      contentWrapper.setPreferredSize(new Dimension(prefContentSize.width, height))
      contentWrapper.revalidate()
    }
  }

  def updateAnimated (expand: Boolean) = {
    val nTicks = prefContentSize.height / 4
    val delay = 20 // ms

    if (content.peer.getWidth == 0) { // first time this is expanded, layout content
      content.peer.setBounds(0, 0, prefContentSize.width, prefContentSize.height)
      content.peer.doLayout()
    }

    val timer = new Timer (delay, null)
    timer.addActionListener( new TimerAction(timer, nTicks, expand))
    timer.start
  }

  /**
    * this has to be a JPanel instead of a scala.swing.BorderPanel since we need
    * to override doLayout - we want a sliding effect for the animation that leaves
    * the content untouched by using the wrapper as a dynamic clipper
    */
  class ContentWrapper (var content: Component) extends JPanel(new BorderLayout) {
    var isAnimating = false
    add(content.peer, BorderLayout.CENTER)
    setOpaque(false)

    override def doLayout(): Unit = {
      if (!isAnimating) {   // otherwise we ignore
        super.doLayout()
      }
    }

    def setContent (newContent: Component) = {
      remove(content.peer)
      content = newContent
      add(content.peer, BorderLayout.CENTER)
      content.revalidate()
      setPreferredSize(null)
    }
  }

  class TimerAction (val timer: Timer, val nTicks: Int, expand: Boolean) extends ActionListener {
    var n = 0
    var y, yLast: Double = 0;
    var dx: Double = 2.0 / nTicks
    val w = contentWrapper.getWidth
    val h = prefContentSize.height

    contentWrapper.isAnimating = true

    def actionPerformed (e: ActionEvent): Unit = {
      n = n + 1
      if (n == nTicks) {
        timer.stop
        y = 1.0
        if (!expand) content.visible = false
        contentWrapper.isAnimating = false

      } else {
        val x = dx * n
        y = x / (0.35 + 0.85 * x)
        if (expand && n == 1) content.visible = true   // make visible before starting animation
      }

      if (!expand) y = 1.0 - y
      if (y != yLast) {
        yLast = y
        contentWrapper.setPreferredSize(w, (h * y).toInt)
        contentWrapper.revalidate()
      }
    }
  }
}
