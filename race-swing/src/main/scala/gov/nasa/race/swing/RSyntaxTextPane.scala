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
import javax.swing.border.EmptyBorder

import gov.nasa.race.swing.Style._
import javax.swing.{JMenuItem, ScrollPaneConstants}
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.{RTextArea, RTextScrollPane}
import scala.swing._
import scala.swing.event.ValueChanged

/**
  * scala-swing wrapper for RSyntaxTextArea
  */
class RSTextArea extends TextComponent {
  class RSTA extends RSyntaxTextArea with SuperMixin {
    // to be able to override methods
  }

  override lazy val peer: RSyntaxTextArea = new RSTA

  private var ignoreChange = false
  var modified: Boolean = false
  var modifiedAction: ()=>Any = ()=>{}
  var saveAction: ()=> Any = null

  override def text_= (s:String) = {
    ignoreChange = true
    super.text_=(s)
    ignoreChange = false
  }

  def syntaxEditingStyle: String = peer.getSyntaxEditingStyle
  def syntaxEditingStyle_=(style: String) = peer.setSyntaxEditingStyle(style)

  def codeFoldingEnabled: Boolean = peer.isCodeFoldingEnabled
  def codeFoldingEnabled_=(b: Boolean) = peer.setCodeFoldingEnabled(b)

  reactions += {
    case ValueChanged(_) =>
      if (!ignoreChange) {
        modifiedAction()
        modified = true
      }
  }

  def onModify (a: =>Any) = modifiedAction = () => a
  def onSave (a: => Any) = {
    if (saveAction == null) { // add a "save" menu item
      val popup = peer.getPopupMenu
      popup.addSeparator

      val mi = new JMenuItem("Save")
      mi.addActionListener( new ActionListener {
        override def actionPerformed(e: ActionEvent) = {if (saveAction != null) saveAction()}
      })
      popup.add(mi)
    }

    saveAction = () => a
  }



  //... and lots more
}

class RSTScrollPane (c: RSTextArea) extends ScrollPane(c) {
  override lazy val peer = new RTextScrollPane(c.peer)

  peer.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)
}

class RSTextPanel extends BorderPanel {
  val editor = new RSTextArea
  editor.border = new EmptyBorder(0,5,0,2) // add extra space between gutter and editor
  val scrollPane = new RSTScrollPane(editor).styled() // gets its style when theming the editor

  layout(scrollPane) = BorderPanel.Position.Center

  editor.styled()

  def text = editor.text
  def text_= (t: String) = editor.text = t
  def modified = editor.modified
  def modified_= (b: Boolean) = editor.modified = b
  def onModify (a: => Any) = editor.onModify(a)
  def onSave (a: => Any) = editor.onSave(a)

}