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

package gov.nasa.race.ui

import com.typesafe.config.Config
import gov.nasa.race.swing.RSTextPanel
import gov.nasa.race.swing.Style._

import scala.swing.BorderPanel

/**
  * RaceConsole page to display and edit selected RACE configuration files
  */
class ConfigEditorPage(raceConsole: GUIMain, pageConfig: Config) extends BorderPanel {
  val editorPanel = new RSTextPanel {
    editor.syntaxEditingStyle = "text/c"
    editor.codeFoldingEnabled = true
  } styled()

  layout(editorPanel) = BorderPanel.Position.Center

  //--- delegations
  def text = editorPanel.text
  def text_= (s: String) = editorPanel.text = s
  def modified = editorPanel.modified
  def modified_= (b: Boolean) = editorPanel.modified = b
  def onModify (a: => Any) = editorPanel.onModify(a)
  def onSave (a: => Any) = editorPanel.onSave(a)
}
