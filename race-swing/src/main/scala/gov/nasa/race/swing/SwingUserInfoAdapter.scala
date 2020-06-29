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

import gov.nasa.race.common.UserInfoAdapter
import gov.nasa.race.swing.GBPanel.{Anchor,Fill,Grid}
import scala.swing.Dialog._
import scala.swing.Swing.EmptyIcon
import scala.swing._

/**
  * generic Swing based implementation of jcraft.jsch.UserInfo
  *
  * Note that we interpret the original UserInfo to reset the entries upon query, i.e.
  * we reset before prompting and after retrieval, to make sure passwords and passphrases
  * are only stored temporarily
  */

trait SwingUserInfoAdapter extends UserInfoAdapter {

  def toplevel: Frame // to be implemented by concrete type to get owning toplevel

  override def promptYesNo (prompt: String): Boolean = showConfirmation(message=prompt) == Result.Yes
  override def showMessage (msg: String): Unit = showMessage(msg)

  override def promptKeyboardInteractive (destination: String, name: String, instruction: String,
                                          prompts: Array[String], echos: Array[Boolean]): Array[String] = {
    val dialog = new Dialog(toplevel)
    dialog.title = s"$destination: $name"
    var canceled = false
    val entries: Array[TextField] = echos.map( if (_) new TextField(15) else new PasswordField(15))
    val okButton = new Button(Action("Ok"){ dialog.close() })
    val cancelButton = new Button(Action("Cancel") { canceled = true; dialog.close() })
    val buttons = new FlowPanel(FlowPanel.Alignment.Trailing)(cancelButton, okButton)

    dialog.contents = new GBPanel {
      val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West, ipadx = 10, insets = (2, 2, 2, 2))
      layout(new Label(instruction)) = c
      for ((prompt,entry) <- prompts.zip(entries)) {
        layout(new Label(prompt,EmptyIcon,Alignment.Right)) = c.grid(0,c.gridy+1)
        layout(entry) = c.gridx(1).weightx(0.5)
      }
      layout(buttons) = c(prompts.length,1).anchor(Anchor.East).weightx(0).gridwidth(1)
    }

    dialog.modal = true
    dialog.open()

    if (canceled) {
      null
    } else entries.map { e =>
      if (e.isInstanceOf[PasswordField]) new String(e.asInstanceOf[PasswordField].password)
      else e.text
    }
  }

  override protected[this] def entryPrompt (prompt: String, storeAction: (Array[Char])=>Unit, getAction: =>Array[Char]): Boolean = {
    val dialog = new Dialog(toplevel)
    val entryField = new PasswordField()
    entryField.action = Action("Enter") { storeAction(entryField.password); dialog.close() }
    val okButton = new Button(Action("Ok"){ storeAction(entryField.password); dialog.close() })
    val cancelButton = new Button(Action("Cancel") { dialog.close() })
    val buttons = new FlowPanel(FlowPanel.Alignment.Trailing)(cancelButton, okButton)

    dialog.title = prompt
    dialog.contents = new GBPanel {
      val c = new Constraints(fill = Fill.Horizontal, anchor = Anchor.West, ipadx=10, insets = (2,2,2,2))
      layout(entryField) = c(0,0).weightx(0.5f).gridwidth(Grid.Remainder)
      layout(buttons) = c(0,1).anchor(Anchor.East).weightx(0).gridwidth(1)
    }
    dialog.modal = true
    dialog.size = (300, 100)

    storeAction(null)
    dialog.open()
    getAction != null
  }
}
